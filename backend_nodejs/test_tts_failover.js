/**
 * Unit tests for ElevenLabs key state and failure classification (SPEC-0002).
 * No network, no keys, no device.
 *
 * A SPEC-0001 (failover para um segundo provedor) foi revogada — ver ADR-0002.
 * O que se testa aqui é o que sobrou e importa: distinguir chave recusada de chave
 * ausente e de falha de conteúdo, que é o que torna o silêncio diagnosticável.
 */
import assert from 'assert';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import {
  resetKeyProbeCache,
  verifyApiKey,
  verifyApiKeyCached,
} from './services/elevenLabsClient.js';
import {
  clearTtsFailure,
  hasTtsKey,
  isTtsAuthOrQuotaError,
  noteTtsFailure,
  ttsFailureReason,
  ttsKeyEnvNames,
  ttsKeySource,
  ttsStatus,
} from './services/ttsProvider.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ─── env sandbox ────────────────────────────────────────────
const TOUCHED = [
  'ELEVENLABS_API_KEY',
  'My-English-Coach-Key',
  'MY_ENGLISH_COACH_KEY',
  'ELEVEN_LABS_API_KEY',
  'ELEVENLABS_KEY',
];
const ORIGINAL = Object.fromEntries(TOUCHED.map((k) => [k, process.env[k]]));

/** Marcador, não credencial. */
function setKey(presente) {
  for (const k of TOUCHED) delete process.env[k];
  if (presente) process.env.ELEVENLABS_API_KEY = 'test-marker-not-a-key';
}

function quiet(fn) {
  const warn = console.warn;
  console.warn = () => {};
  try {
    return fn();
  } finally {
    console.warn = warn;
  }
}

// ─── classificação de erro ──────────────────────────────────

// O erro real do device (print 2026-08-26): 400 com authentication_error.
assert.strictEqual(
  isTtsAuthOrQuotaError(
    new Error('ElevenLabs REST TTS 400: {"detail":{"type":"authentication_error"}}')
  ),
  true,
  'authentication_error dentro de um 400 é falha de credencial, não de conteúdo'
);

assert.strictEqual(isTtsAuthOrQuotaError({ status: 401 }), true);
assert.strictEqual(isTtsAuthOrQuotaError({ status: 429 }), true);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('elevenlabs_api_key_missing')), true);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('quota exceeded for this month')), true);

// Falha de conteúdo não pode ser confundida com chave morta: o conserto é outro.
assert.strictEqual(
  isTtsAuthOrQuotaError(new Error('ElevenLabs REST returned empty audio')),
  false
);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('first_audio_byte_timeout')), false);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('voice_open_failed')), false);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('socket hang up')), false);
assert.strictEqual(isTtsAuthOrQuotaError(null), false);

// ─── taxonomia de reason ────────────────────────────────────
setKey(false);
assert.strictEqual(
  ttsFailureReason(new Error('qualquer coisa')),
  'no_key_configured',
  'sem chave, a razão é a ausência — não adianta culpar a API'
);

setKey(true);
assert.strictEqual(
  ttsFailureReason(new Error('ElevenLabs REST TTS 400: {"type":"authentication_error"}')),
  'elevenlabs_auth_failed'
);
assert.strictEqual(ttsFailureReason({ status: 429 }), 'elevenlabs_quota_exceeded');
assert.strictEqual(
  ttsFailureReason(new Error('first_audio_byte_timeout')),
  'tts_failed',
  'timeout não pode ser reportado como problema de chave'
);

// ─── o estado que responde "por que está mudo" ──────────────
setKey(true);
clearTtsFailure();
assert.strictEqual(ttsStatus().state, 'ready');

quiet(() => noteTtsFailure(new Error('ElevenLabs REST TTS 400: authentication_error')));
const recusada = ttsStatus();
assert.strictEqual(
  recusada.state,
  'key_rejected',
  'este é o caso que /health escondia: chave presente e recusada'
);
assert.strictEqual(recusada.hasKey, true, 'a chave existe — é justamente o que confundia');
assert.strictEqual(recusada.lastFailure.reason, 'elevenlabs_auth_failed');

quiet(() => noteTtsFailure(new Error('first_audio_byte_timeout')));
assert.strictEqual(ttsStatus().state, 'failing', 'falha de conteúdo não vira "chave recusada"');

clearTtsFailure();
assert.strictEqual(ttsStatus().state, 'ready');
assert.strictEqual(ttsStatus().lastFailure, null);

setKey(false);
assert.strictEqual(ttsStatus().state, 'no_key');

// R2/R3 — o diagnóstico nunca pode carregar a chave nem o corpo cru do erro.
setKey(true);
quiet(() =>
  noteTtsFailure(new Error('ElevenLabs 401 para chave test-marker-not-a-key: unauthorized'))
);
const serializado = JSON.stringify(ttsStatus());
assert.ok(
  !serializado.includes('test-marker-not-a-key'),
  'R2: /health/tts jamais pode devolver o valor da chave'
);
assert.ok(
  !serializado.includes('unauthorized'),
  'R2: o corpo cru do erro não pode ser armazenado — só a razão classificada'
);
clearTtsFailure();

// ─── guarda de sincronia das env vars ───────────────────────
// A lista de aliases é duplicada de elevenLabsClient.apiKey() de propósito (importar
// aquele módulo puxaria `ws` e mataria a testabilidade offline). A duplicação só é
// aceitável porque este bloco falha quando as duas divergem.
const fonteElevenLabs = fs.readFileSync(
  path.join(__dirname, 'services', 'elevenLabsClient.js'),
  'utf8'
);
const corpoApiKey = fonteElevenLabs.slice(
  fonteElevenLabs.indexOf('export function apiKey()'),
  fonteElevenLabs.indexOf('export function hasElevenLabsKey()')
);
assert.ok(
  corpoApiKey.length > 50,
  'não achei o corpo de apiKey() em elevenLabsClient.js — a guarda precisa ser reescrita'
);

// Aceita as formas plausíveis de acesso, não só a que existe hoje: aspas simples,
// aspas duplas, sem colchete e optional chaining. Acesso computado (process.env[v])
// continua invisível — está registrado como limitação conhecida no ciclo 3.
const aliasesDoCliente = [
  ...corpoApiKey.matchAll(/process\.env\??(?:\.([A-Za-z0-9_]+)|\[\s*['"]([^'"]+)['"]\s*\])/g),
].map((m) => m[1] || m[2]);

assert.ok(aliasesDoCliente.length >= 3, 'esperava vários aliases em apiKey()');

const conhecidos = ttsKeyEnvNames();
const retrato = [...conhecidos];
const faltando = aliasesDoCliente.filter((a) => !conhecidos.includes(a));
assert.deepStrictEqual(
  faltando,
  [],
  `elevenLabsClient.apiKey() lê ${faltando.join(', ')}, mas ttsProvider não reconhece. ` +
    'O diagnóstico diria "sem chave" enquanto o cliente acha que há uma.'
);

// A lista devolvida é cópia. O retrato é tirado ANTES do push: sem isso, se a função
// devolvesse o array interno, os dois lados da comparação seriam o mesmo objeto mutado.
ttsKeyEnvNames().push('ENV_INVENTADA_PELO_CHAMADOR');
assert.deepStrictEqual(ttsKeyEnvNames(), retrato, 'ttsKeyEnvNames precisa devolver cópia');

// Cada alias declarado de fato ativa a detecção.
for (const envName of conhecidos) {
  setKey(false);
  process.env[envName] = 'test-marker-not-a-key';
  assert.strictEqual(ttsKeySource(), envName, `alias ${envName} declarado mas não reconhecido`);
  assert.strictEqual(hasTtsKey(), true);
}

// ─── sonda de chave (SPEC-0002) ─────────────────────────────
// Sem rede: o fetch é trocado. O que se testa é a classificação, que é o que
// /health/tts usa para dizer "existe chave" versus "a chave foi recusada".
const fetchOriginal = globalThis.fetch;

function fetchQueRetorna(status) {
  return async () => ({ ok: status >= 200 && status < 300, status });
}

try {
  setKey(false);
  resetKeyProbeCache();
  globalThis.fetch = () => assert.fail('não pode ir à rede sem chave');
  assert.deepStrictEqual(await verifyApiKey(), {
    ok: false,
    status: null,
    error: 'elevenlabs_api_key_missing',
  });

  setKey(true);

  resetKeyProbeCache();
  globalThis.fetch = fetchQueRetorna(200);
  const boa = await verifyApiKey();
  assert.strictEqual(boa.ok, true);
  assert.strictEqual(boa.error, null);

  // 401 e 403 são o caso do print: chave presente e recusada.
  for (const status of [401, 403]) {
    globalThis.fetch = fetchQueRetorna(status);
    const r = await verifyApiKey();
    assert.strictEqual(r.ok, false);
    assert.strictEqual(r.error, 'key_rejected', `${status} precisa virar key_rejected`);
  }

  // Erro de servidor NÃO é chave recusada — o conserto é outro.
  globalThis.fetch = fetchQueRetorna(500);
  const r500 = await verifyApiKey();
  assert.strictEqual(r500.error, 'http_500');
  assert.notStrictEqual(r500.error, 'key_rejected');

  // Rede fora também não acusa a chave.
  globalThis.fetch = async () => {
    throw new Error('getaddrinfo ENOTFOUND');
  };
  assert.strictEqual((await verifyApiKey()).error, 'probe_network_error');

  globalThis.fetch = async () => {
    const e = new Error('aborted');
    e.name = 'AbortError';
    throw e;
  };
  assert.strictEqual((await verifyApiKey()).error, 'probe_timeout');

  // R2 — nenhum resultado da sonda pode carregar a chave.
  globalThis.fetch = fetchQueRetorna(401);
  assert.ok(
    !JSON.stringify(await verifyApiKey()).includes('test-marker-not-a-key'),
    'R2: o resultado da sonda não pode conter a chave'
  );

  // Cache: a segunda chamada não vai à rede…
  resetKeyProbeCache();
  let idas = 0;
  globalThis.fetch = async () => {
    idas += 1;
    return { ok: false, status: 401 };
  };
  const p1 = await verifyApiKeyCached();
  const p2 = await verifyApiKeyCached();
  assert.strictEqual(idas, 1, 'a segunda chamada precisa vir do cache');
  assert.strictEqual(p1.cached, false);
  assert.strictEqual(p2.cached, true);

  // …mas trocar a chave invalida o cache na hora. Sem isso, um "recusada" velho
  // sobreviveria à correção da chave no painel — e o diagnóstico mentiria.
  process.env.ELEVENLABS_API_KEY = 'test-marker-not-a-key-DIFERENTE';
  const p3 = await verifyApiKeyCached();
  assert.strictEqual(idas, 2, 'chave nova precisa forçar nova sonda');
  assert.strictEqual(p3.cached, false);
} finally {
  globalThis.fetch = fetchOriginal;
  resetKeyProbeCache();
}

// ─── restaura o ambiente ────────────────────────────────────
clearTtsFailure();
for (const k of TOUCHED) {
  if (ORIGINAL[k] === undefined) delete process.env[k];
  else process.env[k] = ORIGINAL[k];
}

console.log('✅ tts key state + failure classification + key probe tests passed');
