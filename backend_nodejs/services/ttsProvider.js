/**
 * Provider-level TTS failover (SPEC-0001).
 *
 * The pre-existing fallback chain lives *inside* ElevenLabs: main voice → fallback
 * voice → REST complete → text-only. All four steps share one API key, so a rejected
 * key silences the tutor completely. This module sits one level above that chain and
 * answers a single question: which provider should we try right now?
 *
 * Order: elevenlabs → cartesia → (caller degrades to text-only).
 *
 * Cooldown mirrors services/llmClient.js (markClaudeUnavailable / shouldSkipClaude):
 * after an auth/quota failure a provider is skipped for a while, so a dead key costs
 * one failed attempt instead of one per turn. State is in-memory and resets with the
 * process — accepted in SPEC-0001 (E8).
 *
 * No network, no SDK, no I/O: this module is pure selection logic.
 */

/** @typedef {'elevenlabs'|'cartesia'} ProviderName */

export const PROVIDER_ELEVENLABS = 'elevenlabs';
export const PROVIDER_CARTESIA = 'cartesia';

/** Primary first. Text-only is the caller's business, not a provider. */
const PROVIDER_ORDER = [PROVIDER_ELEVENLABS, PROVIDER_CARTESIA];

/**
 * Env names that may carry each provider's key, most canonical first.
 *
 * The ElevenLabs list mirrors elevenLabsClient.apiKey() on purpose: importing that
 * module here would pull `ws` and its module-level setup just to answer "is there a
 * key?", and would make this module (and its test) impossible to run offline.
 * Keep the two lists in sync — an alias added there belongs here too.
 */
const KEY_ENV_NAMES = {
  [PROVIDER_ELEVENLABS]: [
    'ELEVENLABS_API_KEY',
    'My-English-Coach-Key',
    'MY_ENGLISH_COACH_KEY',
    'ELEVEN_LABS_API_KEY',
    'ELEVENLABS_KEY',
  ],
  [PROVIDER_CARTESIA]: ['CARTESIA_API_KEY'],
};

/** Epoch ms until which each provider is skipped. 0 = ready. */
const cooldownUntil = {
  [PROVIDER_ELEVENLABS]: 0,
  [PROVIDER_CARTESIA]: 0,
};

/** Read at call time, never at import: tests and Render both set env late. */
function defaultCooldownMs() {
  const fromEnv = Number(process.env.TTS_PROVIDER_COOLDOWN_MS);
  return Number.isFinite(fromEnv) && fromEnv > 0 ? fromEnv : 10 * 60 * 1000;
}

/**
 * Which env var supplied this provider's key. Never the value itself.
 * @param {ProviderName} name
 * @returns {string|null}
 */
export function providerKeySource(name) {
  for (const envName of KEY_ENV_NAMES[name] || []) {
    if ((process.env[envName] || '').trim()) return envName;
  }
  return null;
}

/**
 * @param {ProviderName} name
 * @returns {boolean} true when any known key env for this provider is set.
 */
export function providerHasKey(name) {
  return providerKeySource(name) !== null;
}

/**
 * Auth / quota failure — the provider itself is unusable for a while, so retrying it
 * this turn (or next turn) only costs latency.
 *
 * Deliberately NOT true for content-level failures (empty audio, first-byte timeout,
 * socket drop): those are worth retrying and must not trigger a cooldown (E5).
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
 * Put a provider on the bench.
 *
 * @param {ProviderName} name
 * @param {string} [reason] — short label for the log. Never a key value.
 * @param {number} [cooldownMs] — defaults to TTS_PROVIDER_COOLDOWN_MS (10 min).
 *   A value <= 0 clears the cooldown instead of setting one.
 */
export function markProviderUnavailable(name, reason = '', cooldownMs) {
  if (!(name in cooldownUntil)) return;

  const ms = cooldownMs === undefined ? defaultCooldownMs() : Number(cooldownMs);
  if (!Number.isFinite(ms) || ms <= 0) {
    cooldownUntil[name] = 0;
    return;
  }

  cooldownUntil[name] = Date.now() + ms;
  console.warn(
    `[tts] ${name} skipped for ${Math.round(ms / 60000)}min — ${reason || 'auth/quota'}`
  );
}

/**
 * @param {ProviderName} name
 * @returns {boolean} has a key AND is not cooling down.
 */
export function isProviderAvailable(name) {
  if (!(name in cooldownUntil)) return false;
  if (!providerHasKey(name)) return false;
  return Date.now() >= cooldownUntil[name];
}

/**
 * Providers worth trying right now, best first.
 * Empty array means every provider is out — the caller degrades to text-only
 * without touching the network (E7).
 *
 * @returns {ProviderName[]}
 */
export function preferredTtsProviderOrder() {
  return PROVIDER_ORDER.filter(isProviderAvailable);
}

/**
 * Diagnostic snapshot for GET /health/tts.
 * Contains no key values — only which env name supplied each key (R3).
 *
 * @returns {{ order: ProviderName[], providers: Array<{ name: ProviderName, hasKey: boolean, keySource: string|null, cooldownUntil: number|null }> }}
 */
export function ttsProviderStatus() {
  const now = Date.now();
  return {
    order: preferredTtsProviderOrder(),
    providers: PROVIDER_ORDER.map((name) => ({
      name,
      hasKey: providerHasKey(name),
      keySource: providerKeySource(name),
      cooldownUntil: cooldownUntil[name] > now ? cooldownUntil[name] : null,
    })),
  };
}
