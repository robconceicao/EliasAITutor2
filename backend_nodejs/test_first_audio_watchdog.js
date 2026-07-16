/**
 * Unit tests for first-audio-byte watchdog (Fase 1 A.2 / D8).
 * No network — pure timing/logic.
 */
import assert from 'assert';
import {
  createFirstAudioWatchdog,
  FIRST_AUDIO_BYTE_TIMEOUT_MS,
} from './services/elevenLabsClient.js';

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function run() {
  assert.ok(FIRST_AUDIO_BYTE_TIMEOUT_MS >= 1000, 'default timeout should be >= 1s');
  assert.strictEqual(FIRST_AUDIO_BYTE_TIMEOUT_MS, 8000, 'D8 default is 8s');

  // 1) First audio cancels timeout
  let timedOut = false;
  let firstAudio = false;
  const w1 = createFirstAudioWatchdog({
    timeoutMs: 80,
    onTimeout: () => {
      timedOut = true;
    },
    onFirstAudio: () => {
      firstAudio = true;
    },
    label: 'test-ok',
  });
  w1.arm();
  w1.noteMessage({ audio: 'base64fake' });
  await sleep(120);
  assert.strictEqual(timedOut, false, 'must not timeout after audio');
  assert.strictEqual(firstAudio, true, 'onFirstAudio called');
  assert.strictEqual(w1.hasAudio, true);

  // 2) Timeout fires when no audio
  let timedOut2 = false;
  const w2 = createFirstAudioWatchdog({
    timeoutMs: 50,
    onTimeout: () => {
      timedOut2 = true;
    },
    label: 'test-timeout',
  });
  w2.arm();
  await sleep(100);
  assert.strictEqual(timedOut2, true, 'must timeout without audio');
  assert.strictEqual(w2.hasAudio, false);

  // 3) cancel prevents timeout
  let timedOut3 = false;
  const w3 = createFirstAudioWatchdog({
    timeoutMs: 40,
    onTimeout: () => {
      timedOut3 = true;
    },
  });
  w3.arm();
  w3.cancel();
  await sleep(80);
  assert.strictEqual(timedOut3, false, 'cancel must prevent timeout');

  // 4) arm is one-shot; double-arm does not reset after audio
  let timeoutCount = 0;
  const w4 = createFirstAudioWatchdog({
    timeoutMs: 30,
    onTimeout: () => {
      timeoutCount += 1;
    },
  });
  w4.arm();
  w4.arm(); // no-op
  await sleep(80);
  assert.strictEqual(timeoutCount, 1, 'single timeout');

  // 5) noteMessage without audio payload is ignored
  let fa = false;
  const w5 = createFirstAudioWatchdog({
    timeoutMs: 200,
    onFirstAudio: () => {
      fa = true;
    },
  });
  w5.arm();
  w5.noteMessage({ isFinal: false });
  w5.noteMessage({});
  assert.strictEqual(fa, false);
  w5.noteMessage({ audio: 'x' });
  assert.strictEqual(fa, true);
  w5.cancel();

  // 6) SLOW NETWORK: audio arrives AFTER timeout → still times out (late audio does not save)
  //    Simulates "almost in time but late" relative to a short window.
  let slowTimedOut = false;
  let slowFirst = false;
  const w6 = createFirstAudioWatchdog({
    timeoutMs: 40,
    onTimeout: () => {
      slowTimedOut = true;
    },
    onFirstAudio: () => {
      slowFirst = true;
    },
    label: 'slow-late',
  });
  w6.arm();
  await sleep(70); // past deadline
  assert.strictEqual(slowTimedOut, true, 'slow network past deadline must timeout');
  w6.noteMessage({ audio: 'late-bytes' }); // too late — socket would already be closed in production
  // hasAudio may still become true if noteMessage is called after timeout;
  // production closes the socket so this path is defensive. Timeout already fired once.
  assert.strictEqual(slowTimedOut, true, 'timeout remains the decision');

  // 7) JUST IN TIME: audio arrives before deadline → no timeout (borderline slow but OK)
  let justTimedOut = false;
  const w7 = createFirstAudioWatchdog({
    timeoutMs: 100,
    onTimeout: () => {
      justTimedOut = true;
    },
    label: 'just-in-time',
  });
  w7.arm();
  await sleep(40); // half window — "rede lenta mas dentro dos 8s"
  w7.noteMessage({ audio: 'pcm' });
  await sleep(80);
  assert.strictEqual(justTimedOut, false, 'audio before deadline must NOT timeout');
  assert.strictEqual(w7.hasAudio, true);

  // 8) Document worst-case path to text-only (chat):
  //    first_byte_primary (8s) + open_fallback handshake (5s) + first_byte_fallback (8s)
  //    ≈ 21s max before text-only — NOT 8+5=13s.
  const OPEN_FALLBACK_MS = 5000;
  const worstCaseMs =
    FIRST_AUDIO_BYTE_TIMEOUT_MS + OPEN_FALLBACK_MS + FIRST_AUDIO_BYTE_TIMEOUT_MS;
  assert.strictEqual(worstCaseMs, 21000, 'worst-case text-only path ~21s');
  console.log(
    `  ℹ worst-case text-only: ${FIRST_AUDIO_BYTE_TIMEOUT_MS}ms (primary 1st byte) ` +
      `+ ${OPEN_FALLBACK_MS}ms (fallback open) + ${FIRST_AUDIO_BYTE_TIMEOUT_MS}ms (fallback 1st byte) ` +
      `= ${worstCaseMs}ms`
  );

  console.log('✅ first-audio-byte watchdog tests passed (incl. slow-network cases)');
}

run().catch((e) => {
  console.error('❌', e);
  process.exit(1);
});
