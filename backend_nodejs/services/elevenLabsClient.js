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

/** Liam — clear male (D5 option A). Used when MAIN_CHAT_VOICE_ID is unset. */
export const DEFAULT_MAIN_CHAT_VOICE_ID = 'TX3LPaxmHKxFdv7VOQHJ';

/** Chris — charming male; reserve if primary fails (§8). */
export const DEFAULT_FALLBACK_VOICE_ID = 'iP95p4xoKVk53GoZ742B';

/** Chunk drill default (F7 / D4) — same family as chat but independent env. */
export const DEFAULT_CHUNK_VOICE_ID = 'TX3LPaxmHKxFdv7VOQHJ';

/** @deprecated Legacy — do not use. Expires 2026-12-31. */
export const LEGACY_ADAM_VOICE_ID = 'pNInz6obpgDQGcFmaJcg';

export function resolveMainChatVoiceId() {
  const fromEnv = (process.env.MAIN_CHAT_VOICE_ID || '').trim();
  if (fromEnv && fromEnv !== LEGACY_ADAM_VOICE_ID) return fromEnv;
  if (fromEnv === LEGACY_ADAM_VOICE_ID) {
    console.warn(
      '[elevenLabs] MAIN_CHAT_VOICE_ID is set to Adam (legacy). Ignoring — using Liam default.'
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

/** Streaming model — do not change without a dedicated latency spec. */
export const STREAM_MODEL_ID = process.env.ELEVENLABS_STREAM_MODEL || 'eleven_flash_v2_5';

export const CHAT_VOICE_SETTINGS = {
  stability: 0.5,
  similarity_boost: 0.8,
};

function apiKey() {
  return process.env.ELEVENLABS_API_KEY || '';
}

/**
 * Streaming WS URL — low latency defaults (Task Final v1.0):
 * - model: eleven_flash_v2_5
 * - optimize_streaming_latency=3 (max speed, slight quality tradeoff)
 * - output_format pcm for Opus pipeline (handled after decode path)
 */
export function streamInputUrl(voiceId) {
  const latency = process.env.ELEVENLABS_STREAM_LATENCY || '3';
  const params = new URLSearchParams({
    model_id: STREAM_MODEL_ID,
    optimize_streaming_latency: String(latency),
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
      // generation_config can nudge faster first byte on stream-input
      generation_config: {
        chunk_length_schedule: [50, 90, 120, 150],
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

  if (!apiKey()) {
    const msg = 'ELEVENLABS_API_KEY missing';
    console.error(`[elevenLabs] ${msg} — text-only mode`);
    return { socket: null, voiceId: null, textOnly: true, error: msg };
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
      return { socket, voiceId: vid, textOnly: false, error: null };
    } catch (e) {
      lastErr = e;
      console.warn(`[elevenLabs] voice open failed (${vid}): ${e.message}`);
    }
  }

  const errMsg = lastErr?.message || 'unknown';
  console.error(
    `[elevenLabs] All chat voices failed — text-only mode. last=${errMsg}`
  );
  return { socket: null, voiceId: null, textOnly: true, error: errMsg };
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
