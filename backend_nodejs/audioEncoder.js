/**
 * PCM → Opus encoder for Android OpusAudioPlayer.
 *
 * Prefer native @discordjs/opus; fall back to pure-JS opusscript when the
 * native binary is missing (common on Render free / missing build toolchain).
 * Top-level import of @discordjs/opus MUST NOT crash the process — that was
 * crashing `npm start` with MODULE_NOT_FOUND on the .node binding.
 */
import { createRequire } from 'module';

const require = createRequire(import.meta.url);

/** Opus / Android pipeline (must match OpusAudioPlayer.kt). */
export const SAMPLE_RATE = 48000;
export const CHANNELS = 1;
export const FRAME_SIZE = 960; // 20ms @ 48kHz mono

/**
 * Default ElevenLabs PCM rate for stream-input.
 * pcm_24000 is widely available; pcm_44100/pcm_48000 may require higher tiers.
 * Override via ELEVENLABS_OUTPUT_FORMAT (e.g. pcm_48000).
 */
export const DEFAULT_ELEVENLABS_PCM_RATE = 24000;

/** @type {'native'|'opusscript'|null} */
let resolvedBackend = null;

/** @type {null | (() => { encodeFrame: (pcmFrameBuf: Buffer) => Buffer, backend: string })} */
let frameEncoderFactory = null;

/**
 * Which Opus implementation is active (for /health + logs).
 * @returns {'native'|'opusscript'|'unresolved'}
 */
export function getOpusBackend() {
  if (resolvedBackend) return resolvedBackend;
  try {
    resolveFrameEncoderFactory();
  } catch (_) {
    /* leave unresolved */
  }
  return resolvedBackend || 'unresolved';
}

/**
 * Resolve once: native @discordjs/opus or pure-JS opusscript.
 * @returns {() => { encodeFrame: (pcmFrameBuf: Buffer) => Buffer, backend: string }}
 */
function resolveFrameEncoderFactory() {
  if (frameEncoderFactory) return frameEncoderFactory;

  // 1) Native (@discordjs/opus) — best performance
  try {
    const mod = require('@discordjs/opus');
    const OpusEncoder = mod.OpusEncoder || mod.default?.OpusEncoder || mod.default;
    if (!OpusEncoder) throw new Error('OpusEncoder export missing');
    // Prove the .node binding actually loads (this is where Render often fails)
    const probeEnc = new OpusEncoder(SAMPLE_RATE, CHANNELS);
    const silence = Buffer.alloc(FRAME_SIZE * 2);
    const probe = probeEnc.encode(silence, FRAME_SIZE);
    if (!probe || probe.length < 1) throw new Error('native encode returned empty');
    resolvedBackend = 'native';
    frameEncoderFactory = () => {
      const enc = new OpusEncoder(SAMPLE_RATE, CHANNELS);
      return {
        backend: 'native',
        encodeFrame(pcmFrameBuf) {
          return enc.encode(pcmFrameBuf, FRAME_SIZE);
        },
      };
    };
    return frameEncoderFactory;
  } catch (e) {
    console.warn(
      `[audioEncoder] @discordjs/opus unavailable (${e.message}) — trying opusscript`
    );
  }

  // 2) Pure JS fallback (works on Render without native build tools)
  // opusscript API: encode(pcmBuffer, frameSizeSamples) — 2nd arg is REQUIRED
  // (omitting it throws: Cannot convert "undefined" to int)
  try {
    const OpusScript = require('opusscript');
    const Application =
      OpusScript.Application?.AUDIO ??
      OpusScript.Application?.VOIP ??
      2049; // OPUS_APPLICATION_AUDIO
    const probeEnc = new OpusScript(SAMPLE_RATE, CHANNELS, Application);
    const silence = Buffer.alloc(FRAME_SIZE * 2);
    const probe = probeEnc.encode(silence, FRAME_SIZE);
    if (!probe || (probe.length !== undefined && probe.length < 1)) {
      throw new Error('opusscript encode returned empty');
    }
    resolvedBackend = 'opusscript';
    console.warn('[audioEncoder] Using opusscript (pure JS) Opus backend');
    frameEncoderFactory = () => {
      const enc = new OpusScript(SAMPLE_RATE, CHANNELS, Application);
      return {
        backend: 'opusscript',
        encodeFrame(pcmFrameBuf) {
          // frameSize = samples per channel (960 = 20ms @ 48kHz mono)
          const out = enc.encode(pcmFrameBuf, FRAME_SIZE);
          return Buffer.isBuffer(out) ? out : Buffer.from(out);
        },
      };
    };
    return frameEncoderFactory;
  } catch (e) {
    console.error('[audioEncoder] No Opus backend available:', e.message);
    throw new Error(
      `No Opus encoder available (native + opusscript failed): ${e.message}`
    );
  }
}

/**
 * Create a one-frame encoder adapter (new instance per stream).
 * @returns {{ encodeFrame: (pcmFrameBuf: Buffer) => Buffer, backend: string }}
 */
function createFrameEncoder() {
  return resolveFrameEncoderFactory()();
}

/**
 * Parse sample rate from an ElevenLabs output_format string (e.g. "pcm_24000").
 * @param {string} outputFormat
 * @returns {number}
 */
export function sampleRateFromOutputFormat(outputFormat) {
  const m = String(outputFormat || '').match(/pcm_(\d+)/i);
  if (m) return parseInt(m[1], 10);
  return DEFAULT_ELEVENLABS_PCM_RATE;
}

/**
 * Linear-resample mono Int16 to 48 kHz.
 * @param {Int16Array} src
 * @param {number} srcRate
 * @returns {Int16Array}
 */
function resampleInt16To48k(src, srcRate) {
  if (!src.length) return new Int16Array(0);
  if (srcRate === SAMPLE_RATE) return src;

  const ratio = SAMPLE_RATE / srcRate;
  const outLen = Math.max(1, Math.floor(src.length * ratio));
  const out = new Int16Array(outLen);

  for (let i = 0; i < outLen; i++) {
    const srcPos = i / ratio;
    const i0 = Math.min(Math.floor(srcPos), src.length - 1);
    const i1 = Math.min(i0 + 1, src.length - 1);
    const frac = srcPos - i0;
    const s = src[i0] * (1 - frac) + src[i1] * frac;
    // Clamp to int16
    out[i] = s < -32768 ? -32768 : s > 32767 ? 32767 : Math.round(s);
  }
  return out;
}

/**
 * Read little-endian PCM Int16 samples from a Node Buffer (safe if unaligned).
 * @param {Buffer} buf
 * @returns {Int16Array}
 */
function bufferToInt16LE(buf) {
  const n = Math.floor(buf.byteLength / 2);
  const out = new Int16Array(n);
  for (let i = 0; i < n; i++) {
    out[i] = buf.readInt16LE(i * 2);
  }
  return out;
}

/**
 * Streaming Opus encoder for ElevenLabs PCM Int16 LE chunks.
 * - Resamples input rate → 48 kHz
 * - Keeps residual samples so partial frames are not dropped (avoids clicks)
 *
 * @param {{ inputSampleRate?: number }} [opts]
 * @returns {{ encode: (pcmInt16Buffer: Buffer) => Buffer[], flush: () => Buffer[], reset: () => void, inputSampleRate: number, backend: string }}
 */
export function createPcmInt16OpusEncoder({ inputSampleRate = DEFAULT_ELEVENLABS_PCM_RATE } = {}) {
  const frameEnc = createFrameEncoder();
  /** @type {Buffer} residual Int16 LE at 48 kHz */
  let residual = Buffer.alloc(0);
  const bytesPerFrame = FRAME_SIZE * 2;

  function encodeFramesFromResidual() {
    const opusFrames = [];
    while (residual.length >= bytesPerFrame) {
      const chunk = residual.subarray(0, bytesPerFrame);
      residual = residual.subarray(bytesPerFrame);
      try {
        // Copy: encoder may hold reference; residual is sliced from growing buffer
        const frameBuf = Buffer.from(chunk);
        opusFrames.push(frameEnc.encodeFrame(frameBuf));
      } catch (err) {
        console.error('[OpusEncoder] Frame encoding error:', err.message);
      }
    }
    return opusFrames;
  }

  /**
   * @param {Buffer} pcmInt16Buffer - raw PCM s16le mono from ElevenLabs
   * @returns {Buffer[]} Opus frames
   */
  function encode(pcmInt16Buffer) {
    if (!pcmInt16Buffer || pcmInt16Buffer.length < 2) return [];

    const srcSamples = bufferToInt16LE(pcmInt16Buffer);
    const at48k = resampleInt16To48k(srcSamples, inputSampleRate);
    const newBuf = Buffer.from(at48k.buffer, at48k.byteOffset, at48k.byteLength);
    residual = residual.length ? Buffer.concat([residual, newBuf]) : newBuf;

    return encodeFramesFromResidual();
  }

  /**
   * Pad residual with silence and emit a final frame (call on stream end).
   * @returns {Buffer[]}
   */
  function flush() {
    if (residual.length === 0) return [];
    if (residual.length < bytesPerFrame) {
      const pad = Buffer.alloc(bytesPerFrame - residual.length); // silence
      residual = Buffer.concat([residual, pad]);
    }
    const frames = encodeFramesFromResidual();
    residual = Buffer.alloc(0);
    return frames;
  }

  function reset() {
    residual = Buffer.alloc(0);
  }

  return {
    encode,
    flush,
    reset,
    inputSampleRate,
    backend: frameEnc.backend,
  };
}

/**
 * @deprecated Cartesia-era path: PCM Float32 LE → Opus @ 48 kHz.
 * Prefer createPcmInt16OpusEncoder for ElevenLabs (pcm_* formats).
 * Kept so accidental Float32 sources do not hard-crash.
 *
 * @param {Buffer} pcmFloat32Buffer
 * @returns {Buffer[]}
 */
export function encodePCMToOpus(pcmFloat32Buffer) {
  if (!pcmFloat32Buffer || pcmFloat32Buffer.byteLength < 4) return [];

  const float32Array = new Float32Array(
    pcmFloat32Buffer.buffer,
    pcmFloat32Buffer.byteOffset,
    Math.floor(pcmFloat32Buffer.byteLength / 4)
  );

  const int16Array = new Int16Array(float32Array.length);
  for (let i = 0; i < float32Array.length; i++) {
    const s = Math.max(-1, Math.min(1, float32Array[i]));
    int16Array[i] = s < 0 ? s * 32768 : s * 32767;
  }

  const encoder = createPcmInt16OpusEncoder({ inputSampleRate: SAMPLE_RATE });
  const buf = Buffer.from(int16Array.buffer, int16Array.byteOffset, int16Array.byteLength);
  const frames = encoder.encode(buf);
  const tail = encoder.flush();
  return frames.concat(tail);
}
