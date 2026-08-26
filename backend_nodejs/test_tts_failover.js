/**
 * Unit tests for provider-level TTS failover (SPEC-0001, critério A1).
 * No network, no keys, no device — pure selection logic.
 *
 * Cobre os casos extremos E1, E2, E5 e E7 da spec.
 */
import assert from 'assert';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import {
  hasCartesiaKey,
  resolveCartesiaVoiceId,
  synthesizePcmRest as cartesiaSynthesize,
} from './services/cartesiaClient.js';
import {
  PROVIDER_CARTESIA,
  PROVIDER_ELEVENLABS,
  isTtsAuthOrQuotaError,
  isProviderAvailable,
  markProviderUnavailable,
  preferredTtsProviderOrder,
  providerHasKey,
  providerKeyEnvNames,
  providerKeySource,
  ttsProviderStatus,
} from './services/ttsProvider.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ─── env sandbox ────────────────────────────────────────────
const TOUCHED = [
  'ELEVENLABS_API_KEY',
  'My-English-Coach-Key',
  'MY_ENGLISH_COACH_KEY',
  'ELEVEN_LABS_API_KEY',
  'ELEVENLABS_KEY',
  'CARTESIA_API_KEY',
  'CARTESIA_VOICE_ID',
  'CARTESIA_API_URL',
  'CARTESIA_API_VERSION',
  'CARTESIA_MODEL',
  'CARTESIA_SAMPLE_RATE',
  'TTS_PROVIDER_COOLDOWN_MS',
];
const ORIGINAL = Object.fromEntries(TOUCHED.map((k) => [k, process.env[k]]));

/** Never a real key: these are markers, not credentials. */
function setKeys({ eleven, cartesia }) {
  for (const k of TOUCHED) delete process.env[k];
  if (eleven) process.env.ELEVENLABS_API_KEY = 'test-marker-not-a-key';
  if (cartesia) process.env.CARTESIA_API_KEY = 'test-marker-not-a-key';
}

function clearCooldowns() {
  markProviderUnavailable(PROVIDER_ELEVENLABS, 'reset', 0);
  markProviderUnavailable(PROVIDER_CARTESIA, 'reset', 0);
}

/** markProviderUnavailable logs by design; keep the test output readable. */
function quiet(fn) {
  const warn = console.warn;
  console.warn = () => {};
  try {
    fn();
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
  'authentication_error dentro de um 400 precisa contar como falha de conta'
);

assert.strictEqual(isTtsAuthOrQuotaError({ status: 401 }), true);
assert.strictEqual(isTtsAuthOrQuotaError({ status: 429 }), true);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('elevenlabs_api_key_missing')), true);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('quota exceeded for this month')), true);

// E5 — áudio vazio é falha do provedor, mas NÃO é falha de conta: nada de cooldown.
assert.strictEqual(
  isTtsAuthOrQuotaError(new Error('ElevenLabs REST returned empty audio')),
  false,
  'E5: áudio vazio não pode derrubar o provedor por 10 minutos'
);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('first_audio_byte_timeout')), false);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('voice_open_failed')), false);
assert.strictEqual(isTtsAuthOrQuotaError(new Error('socket hang up')), false);
assert.strictEqual(isTtsAuthOrQuotaError(null), false);

// ─── E1 — chave da ElevenLabs rejeitada, Cartesia assume ────
setKeys({ eleven: true, cartesia: true });
clearCooldowns();

assert.deepStrictEqual(
  preferredTtsProviderOrder(),
  [PROVIDER_ELEVENLABS, PROVIDER_CARTESIA],
  'com as duas chaves, ElevenLabs continua sendo o primário'
);

quiet(() => markProviderUnavailable(PROVIDER_ELEVENLABS, 'authentication_error'));

assert.deepStrictEqual(
  preferredTtsProviderOrder(),
  [PROVIDER_CARTESIA],
  'E1: chave rejeitada tira o primário da fila, sem derrubar o secundário'
);
assert.strictEqual(isProviderAvailable(PROVIDER_ELEVENLABS), false);
assert.strictEqual(isProviderAvailable(PROVIDER_CARTESIA), true);

// E5 (parte 2) — falha de conteúdo não põe ninguém de castigo.
clearCooldowns();
const antes = preferredTtsProviderOrder();
const erroDeConteudo = new Error('ElevenLabs REST returned empty audio');
if (isTtsAuthOrQuotaError(erroDeConteudo)) {
  quiet(() => markProviderUnavailable(PROVIDER_ELEVENLABS, 'empty_audio'));
}
assert.deepStrictEqual(
  preferredTtsProviderOrder(),
  antes,
  'E5: erro de conteúdo não muda a ordem dos provedores'
);

// ─── E7 — os dois em cooldown: degrada sem tocar a rede ─────
quiet(() => {
  markProviderUnavailable(PROVIDER_ELEVENLABS, 'auth');
  markProviderUnavailable(PROVIDER_CARTESIA, 'auth');
});
assert.deepStrictEqual(
  preferredTtsProviderOrder(),
  [],
  'E7: ninguém disponível → lista vazia, o chamador cai para texto'
);
const statusE7 = ttsProviderStatus();
assert.deepStrictEqual(statusE7.order, []);
assert.ok(
  statusE7.providers.every((p) => typeof p.cooldownUntil === 'number' && p.cooldownUntil > Date.now()),
  'E7: /health/tts precisa mostrar até quando cada provedor está de castigo'
);

// Cooldown expirado devolve o provedor à fila.
clearCooldowns();
assert.deepStrictEqual(preferredTtsProviderOrder(), [PROVIDER_ELEVENLABS, PROVIDER_CARTESIA]);

// ─── E2 — nenhuma chave configurada ─────────────────────────
setKeys({ eleven: false, cartesia: false });
clearCooldowns();

assert.deepStrictEqual(
  preferredTtsProviderOrder(),
  [],
  'E2: sem chave nenhuma, não há provedor a tentar'
);
assert.strictEqual(providerHasKey(PROVIDER_ELEVENLABS), false);
assert.strictEqual(providerKeySource(PROVIDER_ELEVENLABS), null);

const statusE2 = ttsProviderStatus();
assert.deepStrictEqual(
  statusE2.providers.map((p) => p.name),
  [PROVIDER_ELEVENLABS, PROVIDER_CARTESIA]
);
assert.ok(
  statusE2.providers.every((p) => p.hasKey === false && p.keySource === null),
  'E2: status sem chave não pode inventar fonte'
);

// R3 — o snapshot de diagnóstico não pode carregar valor de chave.
setKeys({ eleven: true, cartesia: true });
const serializado = JSON.stringify(ttsProviderStatus());
assert.ok(
  !serializado.includes('test-marker-not-a-key'),
  'R3: /health/tts jamais pode devolver o valor da chave'
);
assert.strictEqual(providerKeySource(PROVIDER_ELEVENLABS), 'ELEVENLABS_API_KEY');

// Alias de env do projeto continua sendo reconhecido.
setKeys({ eleven: false, cartesia: false });
process.env['My-English-Coach-Key'] = 'test-marker-not-a-key';
assert.strictEqual(providerKeySource(PROVIDER_ELEVENLABS), 'My-English-Coach-Key');
assert.strictEqual(providerHasKey(PROVIDER_ELEVENLABS), true);

// ─── A10 — state distingue os três casos ────────────────────
function stateOf(nome) {
  return ttsProviderStatus().providers.find((p) => p.name === nome).state;
}

setKeys({ eleven: true, cartesia: false });
clearCooldowns();
assert.strictEqual(stateOf(PROVIDER_ELEVENLABS), 'ready', 'com chave e sem castigo → ready');
assert.strictEqual(stateOf(PROVIDER_CARTESIA), 'no_key', 'sem chave → no_key, não ready');

quiet(() => markProviderUnavailable(PROVIDER_ELEVENLABS, 'authentication_error'));
assert.strictEqual(stateOf(PROVIDER_ELEVENLABS), 'cooling_down');

// O ponto de G2: cooldownUntil sozinho não distingue "pronto" de "sem chave".
const semCastigo = ttsProviderStatus().providers.filter((p) => p.cooldownUntil === null);
assert.strictEqual(semCastigo.length, 1, 'só o Cartesia está fora de cooldown aqui');
assert.strictEqual(
  semCastigo[0].state,
  'no_key',
  'G2: cooldownUntil null NÃO pode ser lido como "pronto" — state é quem responde'
);
clearCooldowns();

// ─── E10 / A9 — guarda de sincronia das env vars ────────────
// D6 aceita a lista de aliases duplicada em ttsProvider APENAS porque este teste
// falha quando ela diverge da lista que elevenLabsClient.apiKey() realmente lê.
// Por isso a fonte é lida do arquivo: um alias novo lá aparece aqui sem ninguém lembrar.
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
  'não achei o corpo de apiKey() em elevenLabsClient.js — a guarda de E10 precisa ser reescrita'
);

const aliasesDoCliente = [
  ...corpoApiKey.matchAll(/process\.env(?:\.([A-Za-z0-9_]+)|\['([^']+)'\])/g),
].map((m) => m[1] || m[2]);

assert.ok(aliasesDoCliente.length >= 3, 'esperava vários aliases em apiKey()');

const conhecidos = providerKeyEnvNames(PROVIDER_ELEVENLABS);

// A lista devolvida é cópia: mexer nela não pode reconfigurar o registro em produção.
// O retrato é tirado ANTES do push — sem isso, se a função devolvesse o array interno,
// os dois lados da comparação seriam o mesmo objeto mutado e o teste passaria cego.
const retrato = [...conhecidos];
providerKeyEnvNames(PROVIDER_ELEVENLABS).push('ENV_INVENTADA_PELO_CHAMADOR');
assert.deepStrictEqual(
  providerKeyEnvNames(PROVIDER_ELEVENLABS),
  retrato,
  'providerKeyEnvNames precisa devolver cópia — o chamador não pode mutar o registro'
);

const faltando = aliasesDoCliente.filter((a) => !conhecidos.includes(a));
assert.deepStrictEqual(
  faltando,
  [],
  `E10: elevenLabsClient.apiKey() lê ${faltando.join(', ')}, mas ttsProvider não reconhece. ` +
    'O registro acharia que não há chave enquanto o cliente acha que há.'
);

// Direção inversa: cada alias que o registro reconhece de fato ativa o provedor.
for (const envName of conhecidos) {
  setKeys({ eleven: false, cartesia: false });
  process.env[envName] = 'test-marker-not-a-key';
  assert.strictEqual(
    providerKeySource(PROVIDER_ELEVENLABS),
    envName,
    `alias ${envName} declarado mas não reconhecido por providerKeySource`
  );
}

// ─── cartesiaClient — lógica, sem rede ──────────────────────
// O contrato de rede do provedor é Q4 (não verificado). O que se testa aqui é o que
// NÃO depende dele: chave, classificação de erro, PCM e vazamento.
const fetchReal = globalThis.fetch;
const logReal = console.log;

/** @param {{status?:number, body?:Buffer|string}} resposta */
function fakeFetch(resposta, capturado = {}) {
  return async (url, init) => {
    capturado.url = url;
    capturado.init = init;
    const status = resposta.status ?? 200;
    const corpo = resposta.body ?? Buffer.alloc(0);
    return {
      ok: status >= 200 && status < 300,
      status,
      async text() {
        return Buffer.isBuffer(corpo) ? corpo.toString('utf8') : String(corpo);
      },
      async arrayBuffer() {
        const b = Buffer.isBuffer(corpo) ? corpo : Buffer.from(String(corpo));
        return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
      },
    };
  };
}

async function rejeitaCom(fn, teste, mensagem) {
  try {
    await fn();
  } catch (e) {
    assert.ok(teste(e), `${mensagem} — erro recebido: ${e.message}`);
    return e;
  }
  assert.fail(`${mensagem} — não lançou`);
}

try {
  console.log = () => {}; // o cliente loga sucesso por design; não polui a suíte

  // Sem chave: falha antes de qualquer rede.
  setKeys({ eleven: false, cartesia: false });
  globalThis.fetch = () => assert.fail('não pode chamar a rede sem chave');
  assert.strictEqual(hasCartesiaKey(), false);
  await rejeitaCom(
    () => cartesiaSynthesize('hello'),
    (e) => e.message === 'cartesia_api_key_missing',
    'sem chave, o cliente precisa falhar cedo'
  );

  setKeys({ eleven: false, cartesia: true });
  assert.strictEqual(hasCartesiaKey(), true, 'D6: a detecção de chave vem do registro');

  // Texto vazio também não vai à rede.
  await rejeitaCom(
    () => cartesiaSynthesize('   '),
    (e) => e.message === 'empty_tts_text',
    'texto vazio não pode virar requisição'
  );

  // Sucesso: PCM devolvido no mesmo formato do cliente da ElevenLabs (D5).
  const capturado = {};
  process.env.CARTESIA_VOICE_ID = 'voz-de-teste';
  globalThis.fetch = fakeFetch({ status: 200, body: Buffer.alloc(4096, 7) }, capturado);
  const ok = await cartesiaSynthesize('good morning');
  assert.ok(Buffer.isBuffer(ok.pcm) && ok.pcm.length === 4096);
  assert.strictEqual(ok.voiceId, 'voz-de-teste', 'CARTESIA_VOICE_ID precisa mandar');
  assert.strictEqual(ok.sampleRate, 24000);
  assert.deepStrictEqual(
    Object.keys(ok).sort(),
    ['pcm', 'sampleRate', 'voiceId'],
    'D5: mesma forma de retorno de elevenLabsClient.synthesizePcmRest'
  );

  // R3 — a chave vai no header, jamais no corpo.
  assert.ok(capturado.init.headers['X-API-Key'], 'a chave precisa ir no header');
  assert.ok(
    !capturado.init.body.includes('test-marker-not-a-key'),
    'R3: a chave não pode aparecer no corpo da requisição'
  );
  assert.ok(capturado.init.body.includes('voz-de-teste'), 'o id da voz precisa ir no corpo');

  // Corte de tamanho: texto gigante não vira requisição gigante.
  globalThis.fetch = fakeFetch({ status: 200, body: Buffer.alloc(200, 1) }, capturado);
  await cartesiaSynthesize('a'.repeat(9000));
  assert.ok(
    JSON.parse(capturado.init.body).transcript.length <= 2500,
    'texto precisa ser cortado antes de ir para a rede'
  );

  // E1 no secundário — 401 precisa ser classificável como falha de conta.
  globalThis.fetch = fakeFetch({ status: 401, body: '{"error":"unauthorized"}' });
  const err401 = await rejeitaCom(
    () => cartesiaSynthesize('hello'),
    (e) => e.status === 401,
    '401 precisa preservar o status no erro'
  );
  assert.strictEqual(
    isTtsAuthOrQuotaError(err401),
    true,
    'E1: 401 do Cartesia precisa derrubar o provedor para cooldown'
  );

  // E5 no secundário — áudio vazio NÃO é falha de conta.
  globalThis.fetch = fakeFetch({ status: 200, body: Buffer.alloc(10, 0) });
  const errVazio = await rejeitaCom(
    () => cartesiaSynthesize('hello'),
    (e) => /empty audio/i.test(e.message),
    'áudio curto demais precisa ser rejeitado'
  );
  assert.strictEqual(
    isTtsAuthOrQuotaError(errVazio),
    false,
    'E5: áudio vazio não pode pôr o Cartesia de castigo por 10 minutos'
  );

  // Voz default quando não há env.
  delete process.env.CARTESIA_VOICE_ID;
  assert.ok(resolveCartesiaVoiceId().length > 10, 'precisa haver voz default no código');
} finally {
  globalThis.fetch = fetchReal;
  console.log = logReal;
}

// ─── restaura o ambiente ────────────────────────────────────
clearCooldowns();
for (const k of TOUCHED) {
  if (ORIGINAL[k] === undefined) delete process.env[k];
  else process.env[k] = ORIGINAL[k];
}

console.log('✅ tts provider failover tests passed (E1, E2, E5, E7, E10 + state + cartesia)');
