/**
 * ElevenLabs TTS — key state and failure classification (SPEC-0002, ADR-0002).
 *
 * ElevenLabs is the Elias' only voice. There is no provider selection here, and no
 * cooldown: with a single provider, benching it guarantees silence for the whole
 * window — including after the key is fixed in the host panel. That was worth the
 * latency saving with two providers; with one it is sabotage (ADR-0002).
 *
 * What survived the reversal, because it is what makes the silence explainable:
 * telling apart a rejected key from a missing one, and from a content-level failure.
 *
 * No network, no SDK, no I/O.
 */

/**
 * Env names that may carry the key, most canonical first.
 *
 * Mirrors elevenLabsClient.apiKey() on purpose: importing that module here would pull
 * `ws` and its module-level setup just to answer "is there a key?", and would make this
 * module impossible to test offline. test_tts_failover.js fails when the two diverge.
 */
const KEY_ENV_NAMES = [
  'ELEVENLABS_API_KEY',
  'My-English-Coach-Key',
  'MY_ENGLISH_COACH_KEY',
  'ELEVEN_LABS_API_KEY',
  'ELEVENLABS_KEY',
];

/** Last failure the caller reported, so /health/tts can say why the tutor went quiet. */
let lastFailure = null;

/**
 * Which env var supplied the key. Never the value.
 * @returns {string|null}
 */
export function ttsKeySource() {
  for (const envName of KEY_ENV_NAMES) {
    if ((process.env[envName] || '').trim()) return envName;
  }
  return null;
}

/** @returns {boolean} */
export function hasTtsKey() {
  return ttsKeySource() !== null;
}

/**
 * Env var names this module recognises — exported so the divergence guard in
 * test_tts_failover.js can compare it with what elevenLabsClient.apiKey() reads.
 * @returns {string[]} a copy; callers must not mutate the registry.
 */
export function ttsKeyEnvNames() {
  return [...KEY_ENV_NAMES];
}

/**
 * Auth / quota failure — the credential or the account is the problem, and no retry
 * fixes it. This is the `400 authentication_error` the tutor died on.
 *
 * Deliberately NOT true for content-level failures (empty audio, first-byte timeout,
 * socket drop): those are worth retrying and say nothing about the key.
 *
 * @param {unknown} err
 * @returns {boolean}
 */
export function isTtsAuthOrQuotaError(err) {
  const status = err?.status ?? err?.statusCode ?? err?.response?.status;
  if (status === 401 || status === 402 || status === 403 || status === 429) return true;

  const msg = String(err?.message || err || '').toLowerCase();
  if (!msg) return false;

  return (
    msg.includes('authentication_error') ||
    msg.includes('invalid_api_key') ||
    msg.includes('api_key_missing') ||
    msg.includes('unauthorized') ||
    msg.includes('forbidden') ||
    msg.includes('quota') ||
    msg.includes('credit') ||
    msg.includes('billing') ||
    msg.includes('payment') ||
    msg.includes('rate_limit') ||
    msg.includes('rate limit') ||
    msg.includes('too many requests') ||
    / 401\b/.test(msg) ||
    / 402\b/.test(msg) ||
    / 403\b/.test(msg) ||
    / 429\b/.test(msg)
  );
}

/**
 * Closed `reason` taxonomy — the Android side picks its message from this, never from
 * the raw API body (SPEC-0002, D2).
 *
 * @param {unknown} err
 * @returns {'elevenlabs_auth_failed'|'elevenlabs_quota_exceeded'|'no_key_configured'|'tts_failed'}
 */
export function ttsFailureReason(err) {
  if (!hasTtsKey()) return 'no_key_configured';

  const msg = String(err?.message || err || '').toLowerCase();
  const status = err?.status ?? err?.statusCode;
  if (status === 429 || msg.includes('quota') || msg.includes('too many requests')) {
    return 'elevenlabs_quota_exceeded';
  }
  return isTtsAuthOrQuotaError(err) ? 'elevenlabs_auth_failed' : 'tts_failed';
}

/**
 * Record why the last synthesis failed. Never store the error body verbatim: it can
 * echo request material. Only the classified reason and the moment (R2).
 *
 * @param {unknown} err
 */
export function noteTtsFailure(err) {
  lastFailure = { reason: ttsFailureReason(err), at: Date.now() };
  console.warn(`[tts] falha classificada: ${lastFailure.reason}`);
}

/** Clear after a successful synthesis. */
export function clearTtsFailure() {
  lastFailure = null;
}

/**
 * Diagnostic snapshot for GET /health/tts. No key values — only the env var name (R3).
 *
 * `state` exists because `hasKey` alone cannot answer "why is the tutor quiet": a
 * rejected key and a working one are both `hasKey: true`. That ambiguity is what hid
 * the 2026-08-26 outage for two cycles.
 *
 * @returns {{ hasKey: boolean, keySource: string|null, state: 'ready'|'no_key'|'key_rejected'|'failing', lastFailure: {reason: string, at: number}|null }}
 */
export function ttsStatus() {
  const hasKey = hasTtsKey();
  let state = 'ready';
  if (!hasKey) state = 'no_key';
  else if (lastFailure?.reason === 'elevenlabs_auth_failed') state = 'key_rejected';
  else if (lastFailure) state = 'failing';

  return {
    hasKey,
    keySource: ttsKeySource(),
    state,
    lastFailure: lastFailure ? { ...lastFailure } : null,
  };
}

/**
 * Estado final para GET /health/tts, a partir do que foi observado em uso real e do
 * que a sonda acabou de provar (SPEC-0002, D5).
 *
 * A regra que importa: **a sonda só prova o que sondou**. Um 200 na camada de conta
 * diz que a conta responde — não diz que a síntese funciona — e por isso não pode
 * apagar uma falha real já registrada. Sem isso, o payload se contradizia: `ready`
 * ao lado de um `lastFailure` de cota (achado F3 do ciclo 3).
 *
 * @param {{hasKey:boolean, state:string, lastFailure:{reason:string}|null}} observed
 * @param {{ok:boolean|null, error:string|null, method:string|null}} liveCheck
 * @returns {'ready'|'no_key'|'key_rejected'|'quota_exceeded'|'failing'|'unverified'}
 */
export function deriveTtsState(observed, liveCheck = {}) {
  if (!observed?.hasKey) return 'no_key';
  if (liveCheck.error === 'key_rejected') return 'key_rejected';
  if (liveCheck.error === 'quota_exceeded') return 'quota_exceeded';

  // Camada barata não decidiu e ninguém pediu prova de síntese.
  if (liveCheck.error === 'inconclusive') {
    return observed.lastFailure ? observed.state : 'unverified';
  }

  if (liveCheck.ok === true) {
    // Síntese provada agora refuta o histórico; conta respondendo, não.
    if (liveCheck.method === 'tts') return 'ready';
    return observed.lastFailure ? observed.state : 'ready';
  }

  return observed.state;
}
