/**
 * Single access point for ElevenLabs TTS (V6 / F7 / Adam retirement D5).
 *
 * Voice config (independent knobs):
 *   MAIN_CHAT_VOICE_ID  — streaming chat / shadow_speak (default Liam)
 *   FALLBACK_VOICE_ID   — used if primary chat voice fails (default Chris)
 *   CHUNK_VOICE_ID      — pre-generated chunk audio for Modo Programa (default Liam)
 *
 * MAIN_CHAT_VOICE_ID and CHUNK_VOICE_ID are independent: they may share the same
 * value by product choice, but changing one must never silently change the other.
 *
 * Never default to legacy Default voices (Adam, Antoni, Josh, etc.) — expire 2026-12-31.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import ws from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CACHE_DIR = path.resolve(__dirname, '../cache/chunks');

// ─── Voice IDs (code defaults — never Adam) ─────────────────

/**
 * Brian — deep, clear American male narrator (best default for pronunciation tutoring).
 * Override with MAIN_CHAT_VOICE_ID. Liam remains available as env choice.
 */
export const DEFAULT_MAIN_CHAT_VOICE_ID = 'nPczCjzI2devNBz1zQrb';

/** Liam — clear young male American; reserve if primary fails. */
export const DEFAULT_FALLBACK_VOICE_ID = 'TX3LPaxmHKxFdv7VOQHJ';

/** Legacy Chris id kept for env overrides. */
export const CHRIS_VOICE_ID = 'iP95p4xoKVk53GoZ742B';

/** Chunk drill default (F7 / D4) — clear American male, independent of chat env. */
export const DEFAULT_CHUNK_VOICE_ID = 'nPczCjzI2devNBz1zQrb';

/** @deprecated Legacy — do not use. Expires 2026-12-31. */
export const LEGACY_ADAM_VOICE_ID = 'pNInz6obpgDQGcFmaJcg';

export function resolveMainChatVoiceId() {
  const fromEnv = (process.env.MAIN_CHAT_VOICE_ID || '').trim();
  if (fromEnv && fromEnv !== LEGACY_ADAM_VOICE_ID) return fromEnv;
  if (fromEnv === LEGACY_ADAM_VOICE_ID) {
    console.warn(
      '[elevenLabs] MAIN_CHAT_VOICE_ID is set to Adam (legacy). Ignoring — using Brian default.'
    );
  }
  return DEFAULT_MAIN_CHAT_VOICE_ID;
}

export function resolveFallbackVoiceId() {
  const fromEnv = (process.env.FALLBACK_VOICE_ID || '').trim();
  if (fromEnv && fromEnv !== LEGACY_ADAM_VOICE_ID) return fromEnv;
  return DEFAULT_FALLBACK_VOICE_ID;
}

export function resolveChunkVoiceId() {
  const fromEnv = (process.env.CHUNK_VOICE_ID || '').trim();
  if (fromEnv && fromEnv !== LEGACY_ADAM_VOICE_ID) return fromEnv;
  return DEFAULT_CHUNK_VOICE_ID;
}

/** @deprecated Use resolveChunkVoiceId() — kept as alias for existing imports. */
export const CHUNK_VOICE_ID = resolveChunkVoiceId();

export const CHUNK_VOICE_SETTINGS = {
  stability: 0.7,
  similarity_boost: 0.85,
  style: 0.1,
  use_speaker_boost: true,
};

export const CHUNK_MODEL_ID = process.env.CHUNK_TTS_MODEL || 'eleven_flash_v2_5';

/**
 * Streaming model — turbo has clearer English pronunciation than flash
 * while remaining low-latency. Override via ELEVENLABS_STREAM_MODEL.
 * flash is still OK if cost/latency is critical.
 */
export const STREAM_MODEL_ID =
  process.env.ELEVENLABS_STREAM_MODEL || 'eleven_turbo_v2_5';

/**
 * PCM Int16 LE for Opus pipeline (NOT mp3 — default without this is mp3_44100_128,
 * which caused static/hiss when decoded as PCM Float32).
 * pcm_24000 is widely available; pcm_44100/pcm_48000 may need Pro+.
 */
export const STREAM_OUTPUT_FORMAT =
  process.env.ELEVENLABS_OUTPUT_FORMAT || 'pcm_24000';

/**
 * Clear GA teaching voice: higher stability = less drift/weird prosody;
 * higher similarity = more consistent timbre. style low keeps pronunciation clean.
 */
export const CHAT_VOICE_SETTINGS = {
  stability: 0.72,
  similarity_boost: 0.85,
  style: 0.05,
  use_speaker_boost: true,
};

/**
 * Resolve ElevenLabs API key.
 * Supports ELEVENLABS_API_KEY and project aliases (My-English-Coach-Key, etc.).
 * Never log the raw key — use resolveApiKeySource() for diagnostics.
 */
export function apiKey() {
  const candidates = [
    process.env.ELEVENLABS_API_KEY,
    process.env['My-English-Coach-Key'],
    process.env.MY_ENGLISH_COACH_KEY,
    process.env.ELEVEN_LABS_API_KEY,
    process.env.ELEVENLABS_KEY,
  ];
  for (const c of candidates) {
    const v = (c || '').trim();
    if (v) return v;
  }
  return '';
}

/** True when any known ElevenLabs key env is present. */
export function hasElevenLabsKey() {
  return Boolean(apiKey());
}

/**
 * Which env name supplied the key (for /health — no secret values).
 * @returns {'ELEVENLABS_API_KEY'|'My-English-Coach-Key'|'MY_ENGLISH_COACH_KEY'|'ELEVEN_LABS_API_KEY'|'ELEVENLABS_KEY'|'none'}
 */
export function resolveApiKeySource() {
  if ((process.env.ELEVENLABS_API_KEY || '').trim()) return 'ELEVENLABS_API_KEY';
  if ((process.env['My-English-Coach-Key'] || '').trim()) return 'My-English-Coach-Key';
  if ((process.env.MY_ENGLISH_COACH_KEY || '').trim()) return 'MY_ENGLISH_COACH_KEY';
  if ((process.env.ELEVEN_LABS_API_KEY || '').trim()) return 'ELEVEN_LABS_API_KEY';
  if ((process.env.ELEVENLABS_KEY || '').trim()) return 'ELEVENLABS_KEY';
  return 'none';
}

/**
 * Normalize env: copy alias into ELEVENLABS_API_KEY so all code paths see it.
 * Call once at boot (server.js also does this for local.properties).
 */
export function ensureElevenLabsKeyEnv() {
  if (!(process.env.ELEVENLABS_API_KEY || '').trim()) {
    const k = apiKey();
    if (k) process.env.ELEVENLABS_API_KEY = k;
  }
  return hasElevenLabsKey();
}

/**
 * Streaming WS URL — quality-first defaults for tutoring:
 * - model: eleven_turbo_v2_5 (clearer than flash)
 * - optimize_streaming_latency=2 (1=best quality, 3=fastest/choppy)
 * - output_format=pcm_* (Int16 LE) for encodePCM→Opus pipeline
 */
export function streamInputUrl(voiceId) {
  const latency = process.env.ELEVENLABS_STREAM_LATENCY || '2';
  const params = new URLSearchParams({
    model_id: STREAM_MODEL_ID,
    optimize_streaming_latency: String(latency),
    output_format: STREAM_OUTPUT_FORMAT,
  });
  return `wss://api.elevenlabs.io/v1/text-to-speech/${voiceId}/stream-input?${params.toString()}`;
}

/**
 * Send ElevenLabs stream-input init frame.
 */
export function sendTtsInit(elevenSocket) {
  elevenSocket.send(
    JSON.stringify({
      text: ' ',
      voice_settings: CHAT_VOICE_SETTINGS,
      xi_api_key: apiKey(),
      // Larger chunks → fuller prosody, less word-chop / engasgo
      generation_config: {
        chunk_length_schedule: [120, 160, 250, 290],
      },
    })
  );
}

/**
 * Open a single TTS input WebSocket and validate the voice.
 *
 * ElevenLabs accepts the WS upgrade even for unknown voiceIds; the real error
 * arrives after the init frame as { error: "voice_id_does_not_exist", ... }
 * and close code 1008. We wait briefly after init to catch that before
 * considering the voice usable.
 *
 * @returns {Promise<import('ws').WebSocket>}
 */
export function openTtsWebSocket(voiceId, { timeoutMs = 8000 } = {}) {
  return new Promise((resolve, reject) => {
    if (!apiKey()) {
      reject(new Error('ELEVENLABS_API_KEY missing'));
      return;
    }
    const WebSocketImpl = global.WebSocket || ws;
    const socket = new WebSocketImpl(streamInputUrl(voiceId));
    let settled = false;

    const fail = (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        socket.close();
      } catch (_) {}
      reject(err instanceof Error ? err : new Error(String(err)));
    };

    const succeed = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      // Remove handshake-only listeners; caller owns the socket from here.
      socket.removeAllListeners?.('message');
      resolve(socket);
    };

    const timer = setTimeout(() => {
      // No error after init → treat as valid (ElevenLabs only errors on bad voice)
      succeed();
    }, timeoutMs);

    socket.on('open', () => {
      try {
        sendTtsInit(socket);
      } catch (e) {
        fail(e);
        return;
      }
      // Short window: invalid voices error almost immediately after init.
      // Valid voices stay quiet until generation — accept after grace period.
      setTimeout(() => {
        if (!settled) succeed();
      }, 600);
    });

    socket.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw.toString());
        if (msg.error || msg.code === 1008) {
          const detail =
            msg.message || msg.error || `ElevenLabs voice error for ${voiceId}`;
          fail(new Error(detail));
        }
      } catch (_) {
        /* binary/non-JSON — ignore during handshake */
      }
    });

    socket.on('error', (err) => fail(err));

    socket.on('close', (code, reason) => {
      if (settled) return;
      const reasonStr = reason?.toString?.() || '';
      if (code === 1008 || /voice/i.test(reasonStr) || /does not exist/i.test(reasonStr)) {
        fail(new Error(reasonStr || `TTS closed code ${code} for voice ${voiceId}`));
      }
    });
  });
}

/**
 * Open streaming TTS with fallback chain (§8):
 * 1) preferred (session-locked MAIN_CHAT)
 * 2) FALLBACK_VOICE_ID
 * 3) text-only (socket null)
 *
 * @param {string} preferredVoiceId
 * @returns {Promise<{ socket: import('ws').WebSocket|null, voiceId: string|null, textOnly: boolean, error: string|null }>}
 */
export async function openTtsWebSocketWithFallback(preferredVoiceId) {
  const primary = preferredVoiceId || resolveMainChatVoiceId();
  const fallback = resolveFallbackVoiceId();
  const candidates = [primary, fallback].filter(
    (v, i, arr) => v && arr.indexOf(v) === i
  );

  ensureElevenLabsKeyEnv();
  if (!apiKey()) {
    const msg = 'elevenlabs_api_key_missing';
    console.error(
      `[elevenLabs] ${msg} — set ELEVENLABS_API_KEY or My-English-Coach-Key on the host (Render env). text-only until fixed.`
    );
    return {
      socket: null,
      voiceId: null,
      textOnly: true,
      restOk: false,
      error: msg,
    };
  }

  let lastErr = null;
  for (const vid of candidates) {
    try {
      const socket = await openTtsWebSocket(vid, { timeoutMs: 5000 });
      if (vid !== primary) {
        console.warn(
          `[elevenLabs] FALLBACK voice activated: ${vid} (primary ${primary} failed)`
        );
      }
      // init already sent during handshake validation
      return {
        socket,
        voiceId: vid,
        textOnly: false,
        restOk: true,
        error: null,
      };
    } catch (e) {
      lastErr = e;
      console.warn(`[elevenLabs] voice open failed (${vid}): ${e.message}`);
    }
  }

  const errMsg = lastErr?.message || 'voice_open_failed';
  // Key present but WS failed — REST complete generation can still work.
  console.warn(
    `[elevenLabs] All stream-input voices failed — will try REST TTS. last=${errMsg}`
  );
  return {
    socket: null,
    voiceId: primary,
    textOnly: false,
    restOk: true,
    error: errMsg,
  };
}

/**
 * Complete (non-streaming) TTS via REST — PCM Int16 LE.
 * Used when WebSocket stream-input fails but the API key is valid.
 *
 * @param {string} text
 * @param {string} [voiceId]
 * @returns {Promise<{ pcm: Buffer, voiceId: string, sampleRate: number }>}
 */
export async function synthesizePcmRest(text, voiceId) {
  ensureElevenLabsKeyEnv();
  const key = apiKey();
  if (!key) throw new Error('elevenlabs_api_key_missing');
  const trimmed = String(text || '').trim();
  if (!trimmed) throw new Error('empty_tts_text');

  const vid = voiceId || resolveMainChatVoiceId();
  const format = STREAM_OUTPUT_FORMAT; // e.g. pcm_24000
  const url = `https://api.elevenlabs.io/v1/text-to-speech/${vid}?output_format=${encodeURIComponent(format)}`;

  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'xi-api-key': key,
      Accept: 'application/octet-stream',
    },
    body: JSON.stringify({
      text: trimmed.slice(0, 2500),
      model_id: STREAM_MODEL_ID,
      voice_settings: CHAT_VOICE_SETTINGS,
    }),
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => '');
    // Try fallback voice once on 4xx voice errors
    if (
      res.status >= 400 &&
      res.status < 500 &&
      vid !== resolveFallbackVoiceId()
    ) {
      console.warn(
        `[elevenLabs] REST TTS ${res.status} for ${vid}, retrying fallback voice`
      );
      return synthesizePcmRest(text, resolveFallbackVoiceId());
    }
    throw new Error(`ElevenLabs REST TTS ${res.status}: ${errText.slice(0, 200)}`);
  }

  const pcm = Buffer.from(await res.arrayBuffer());
  if (pcm.length < 100) throw new Error('ElevenLabs REST returned empty audio');
  const sampleRate = Number(String(format).match(/pcm_(\d+)/i)?.[1] || 24000);
  console.log(
    `[elevenLabs] REST TTS ok voice=${vid} bytes=${pcm.length} format=${format}`
  );
  return { pcm, voiceId: vid, sampleRate };
}

// ─── First audio byte timeout (D8 / Fase 1 A.2) ─────────────

/**
 * Max wait for the first audio payload after TTS text is sent.
 * This is independent of WebSocket open handshake (which can succeed while
 * generation hangs forever — root cause of infinite "Elias não fala" spinner).
 * Override via TTS_FIRST_BYTE_TIMEOUT_MS.
 */
export const FIRST_AUDIO_BYTE_TIMEOUT_MS = Number(
  process.env.TTS_FIRST_BYTE_TIMEOUT_MS || 8000
);

/**
 * One-shot watchdog: arm() when first TTS text is sent; must see audio within timeout.
 *
 * @param {object} opts
 * @param {number} [opts.timeoutMs]
 * @param {() => void} [opts.onTimeout]
 * @param {() => void} [opts.onFirstAudio]
 * @param {string} [opts.label]
 * @returns {{ arm: Function, noteMessage: Function, cancel: Function, hasAudio: boolean, isArmed: boolean }}
 */
export function createFirstAudioWatchdog({
  timeoutMs = FIRST_AUDIO_BYTE_TIMEOUT_MS,
  onTimeout,
  onFirstAudio,
  label = 'tts',
} = {}) {
  let timer = null;
  let gotAudio = false;
  let armed = false;
  let cancelled = false;

  const clear = () => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
  };

  return {
    get hasAudio() {
      return gotAudio;
    },
    get isArmed() {
      return armed;
    },
    arm() {
      if (cancelled || gotAudio || armed) return;
      armed = true;
      timer = setTimeout(() => {
        if (cancelled || gotAudio) return;
        console.warn(
          `[elevenLabs] first-audio-byte timeout after ${timeoutMs}ms (${label})`
        );
        try {
          onTimeout?.();
        } catch (e) {
          console.error('[elevenLabs] onTimeout error:', e?.message || e);
        }
      }, timeoutMs);
    },
    /** Call for each parsed ElevenLabs message object. */
    noteMessage(msg) {
      if (cancelled || gotAudio) return;
      if (msg && (msg.audio || msg.audio_base64)) {
        gotAudio = true;
        clear();
        try {
          onFirstAudio?.();
        } catch (_) {
          /* ignore */
        }
      }
    },
    cancel() {
      cancelled = true;
      clear();
    },
  };
}

// ─── Chunk pre-generation (F7) ──────────────────────────────

export function ensureCacheDir() {
  if (!fs.existsSync(CACHE_DIR)) {
    fs.mkdirSync(CACHE_DIR, { recursive: true });
  }
  return CACHE_DIR;
}

export function chunkAudioPath(week, index) {
  return path.join(
    CACHE_DIR,
    `w${String(week).padStart(2, '0')}_${String(index).padStart(2, '0')}.mp3`
  );
}

export function relativeChunkPath(week, index) {
  return `cache/chunks/w${String(week).padStart(2, '0')}_${String(index).padStart(2, '0')}.mp3`;
}

/**
 * Generate TTS MP3 for text if file does not already exist.
 * @returns {string|null} absolute path or null on failure
 */
export async function synthesizeToFile(text, outPath) {
  if (fs.existsSync(outPath) && fs.statSync(outPath).size > 0) {
    return outPath;
  }
  const key = apiKey();
  if (!key) {
    console.warn('[elevenLabs] ELEVENLABS_API_KEY missing — skip chunk TTS');
    return null;
  }

  ensureCacheDir();
  const voiceId = resolveChunkVoiceId();
  const url = `https://api.elevenlabs.io/v1/text-to-speech/${voiceId}?output_format=mp3_44100_128`;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'xi-api-key': key,
      Accept: 'audio/mpeg',
    },
    body: JSON.stringify({
      text,
      model_id: CHUNK_MODEL_ID,
      voice_settings: CHUNK_VOICE_SETTINGS,
    }),
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => '');
    throw new Error(`ElevenLabs TTS ${res.status}: ${errText.slice(0, 200)}`);
  }

  const buf = Buffer.from(await res.arrayBuffer());
  fs.writeFileSync(outPath, buf);
  return outPath;
}

/**
 * Pre-generate audio for all chunks of a week. Idempotent.
 */
export async function pregenerateWeekChunks(weekDoc) {
  if (!weekDoc?.chunks?.length) return weekDoc;
  ensureCacheDir();
  const updated = [];
  for (let i = 0; i < weekDoc.chunks.length; i++) {
    const c = { ...weekDoc.chunks[i] };
    const abs = chunkAudioPath(weekDoc.week, i);
    try {
      await synthesizeToFile(c.en, abs);
      c.audioPath = relativeChunkPath(weekDoc.week, i);
    } catch (e) {
      console.warn(`[elevenLabs] week ${weekDoc.week} chunk ${i}: ${e.message}`);
      c.audioPath = c.audioPath || null;
    }
    updated.push(c);
  }
  return { ...weekDoc, chunks: updated };
}

export async function listVoices() {
  const key = apiKey();
  if (!key) return [];
  const res = await fetch('https://api.elevenlabs.io/v1/voices', {
    headers: { 'xi-api-key': key },
  });
  if (!res.ok) return [];
  const data = await res.json();
  return data.voices || [];
}
