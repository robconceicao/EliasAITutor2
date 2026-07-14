/**
 * Smoke test: PCM Int16 → Opus (no network).
 * Run: node test_audio_encoder.js
 */
import {
  createPcmInt16OpusEncoder,
  sampleRateFromOutputFormat,
  SAMPLE_RATE,
  FRAME_SIZE,
} from './audioEncoder.js';
import { STREAM_OUTPUT_FORMAT } from './services/elevenLabsClient.js';

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

// 440 Hz tone at 24 kHz, ~100 ms
function makeSinePcmInt16(sampleRate, durationMs, freq = 440) {
  const n = Math.floor((sampleRate * durationMs) / 1000);
  const buf = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) {
    const t = i / sampleRate;
    const s = Math.sin(2 * Math.PI * freq * t) * 0.4;
    buf.writeInt16LE(Math.round(s * 32767), i * 2);
  }
  return buf;
}

const rate = sampleRateFromOutputFormat(STREAM_OUTPUT_FORMAT);
assert(rate === 24000 || rate === 48000 || rate === 44100, `unexpected rate ${rate}`);

const enc = createPcmInt16OpusEncoder({ inputSampleRate: rate });
const pcm = makeSinePcmInt16(rate, 100);
const frames = enc.encode(pcm);
const tail = enc.flush();
const all = frames.concat(tail);

assert(all.length > 0, 'expected at least one Opus frame');
for (const f of all) {
  assert(Buffer.isBuffer(f) && f.length > 0, 'empty opus frame');
  // Typical Opus frame for speech is tens of bytes; reject MP3-like huge blobs
  assert(f.length < 2000, `opus frame suspiciously large: ${f.length}`);
}

// Residual: feed partial frame bytes then complete
const enc2 = createPcmInt16OpusEncoder({ inputSampleRate: SAMPLE_RATE });
const full = makeSinePcmInt16(SAMPLE_RATE, 40); // 2 frames @ 20ms
const half = full.subarray(0, FRAME_SIZE); // half frame in bytes? FRAME_SIZE samples = FRAME_SIZE*2 bytes
// first half samples
const part1 = full.subarray(0, FRAME_SIZE); // FRAME_SIZE bytes = half of one frame samples
const r1 = enc2.encode(part1);
assert(r1.length === 0, 'partial frame should not emit yet');
const r2 = enc2.encode(full.subarray(FRAME_SIZE));
// with residual + rest we should get frames
const r3 = enc2.flush();
assert(r2.length + r3.length >= 1, 'residual path should produce frames');

console.log(
  `[ok] format=${STREAM_OUTPUT_FORMAT} rate=${rate} frames=${all.length} sizes=${all.map((f) => f.length).join(',')}`
);
console.log('[ok] residual framing works');
