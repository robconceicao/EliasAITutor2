/**
 * Unit checks for Adam retirement / voice config (no network).
 */
import assert from 'assert';
import {
  resolveMainChatVoiceId,
  resolveFallbackVoiceId,
  resolveChunkVoiceId,
  DEFAULT_MAIN_CHAT_VOICE_ID,
  DEFAULT_FALLBACK_VOICE_ID,
  LEGACY_ADAM_VOICE_ID,
  STREAM_MODEL_ID,
  streamInputUrl,
  FIRST_AUDIO_BYTE_TIMEOUT_MS,
  createFirstAudioWatchdog,
} from './services/elevenLabsClient.js';

const saved = { ...process.env };

function resetEnv() {
  delete process.env.MAIN_CHAT_VOICE_ID;
  delete process.env.FALLBACK_VOICE_ID;
  delete process.env.CHUNK_VOICE_ID;
}

try {
  resetEnv();
  assert.strictEqual(resolveMainChatVoiceId(), DEFAULT_MAIN_CHAT_VOICE_ID, 'default Liam');
  assert.notStrictEqual(resolveMainChatVoiceId(), LEGACY_ADAM_VOICE_ID, 'not Adam');
  assert.strictEqual(resolveFallbackVoiceId(), DEFAULT_FALLBACK_VOICE_ID, 'default Chris');
  assert.strictEqual(STREAM_MODEL_ID.includes('flash') || STREAM_MODEL_ID.length > 0, true);

  process.env.MAIN_CHAT_VOICE_ID = LEGACY_ADAM_VOICE_ID;
  assert.strictEqual(
    resolveMainChatVoiceId(),
    DEFAULT_MAIN_CHAT_VOICE_ID,
    'Adam env is rejected'
  );

  process.env.MAIN_CHAT_VOICE_ID = 'customVoice123';
  assert.strictEqual(resolveMainChatVoiceId(), 'customVoice123', 'custom main voice');

  process.env.CHUNK_VOICE_ID = 'chunkOnlyVoice';
  assert.strictEqual(resolveChunkVoiceId(), 'chunkOnlyVoice', 'chunk independent');
  assert.strictEqual(resolveMainChatVoiceId(), 'customVoice123', 'chunk does not affect main');

  const url = streamInputUrl(DEFAULT_MAIN_CHAT_VOICE_ID);
  assert.ok(url.includes(DEFAULT_MAIN_CHAT_VOICE_ID));
  assert.ok(url.includes('stream-input'));
  assert.ok(url.includes('eleven_flash_v2_5') || url.includes(STREAM_MODEL_ID));
  assert.ok(url.includes('optimize_streaming_latency'), 'latency query present');
  // pcm_* required for Opus pipeline (mp3 default caused static/hiss)
  assert.ok(url.includes('output_format=pcm_'), 'PCM output_format required for Opus');
  assert.ok(!url.includes(LEGACY_ADAM_VOICE_ID));

  // D8: first-audio-byte timeout is explicit and watchdog is exportable
  assert.strictEqual(FIRST_AUDIO_BYTE_TIMEOUT_MS, 8000, 'D8 first-byte default 8s');
  assert.strictEqual(typeof createFirstAudioWatchdog, 'function');

  console.log('✅ voice config tests passed');
} finally {
  Object.assign(process.env, saved);
  for (const k of ['MAIN_CHAT_VOICE_ID', 'FALLBACK_VOICE_ID', 'CHUNK_VOICE_ID']) {
    if (!(k in saved)) delete process.env[k];
  }
}
