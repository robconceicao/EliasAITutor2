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
  deriveTtsState,
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
  'timeout não é problema de chave'
);

// A10 — o server.js NÃO pode passar esse rótulo por ttsFailureReason: ele já é da
// taxonomia e seria achatado em tts_failed, e o app perderia a distinção (F1 do ciclo 3).
// Este teste lê a fiação do server.js porque foi exatamente ali que o bug morava.
const fonteServer = fs.readFileSync(path.join(__dirname, 'server.js'), 'utf8');
assert.ok(
  /isTaxonomyLabel\(cause\) \? cause : ttsFailureReason\(err\)/.test(fonteServer),
  'A10: goTextOnly precisa preservar rótulo da taxonomia em vez de reclassificar'
);
assert.ok(
  /TTS_TAXONOMY_LABELS[\s\S]{0,300}first_audio_byte_timeout/.test(fonteServer),
  'A10: first_audio_byte_timeout precisa estar na lista de rótulos preservados'
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

// ─── A9 — a sonda só prova o que sondou (D5) ────────────────
const comChave = { hasKey: true, state: 'failing', lastFailure: { reason: 'elevenlabs_quota_exceeded' } };
const limpo = { hasKey: true, state: 'ready', lastFailure: null };

assert.strictEqual(deriveTtsState({ hasKey: false }, {}), 'no_key');

// O caso F3: conta responde 200, mas houve falha de cota registrada. Antes, isto
// devolvia 'ready' ao lado do lastFailure de cota — contradição no mesmo JSON.
assert.strictEqual(
  deriveTtsState(comChave, { ok: true, method: 'account' }),
  'failing',
  'A9: conta OK não refuta uma falha que ela não testou'
);

// Síntese provada agora, sim, refuta o histórico.
assert.strictEqual(
  deriveTtsState(comChave, { ok: true, method: 'tts' }),
  'ready',
  'A9: quem provou síntese pode dizer ready'
);

assert.strictEqual(deriveTtsState(limpo, { ok: true, method: 'account' }), 'ready');
assert.strictEqual(deriveTtsState(limpo, { error: 'key_rejected' }), 'key_rejected');
assert.strictEqual(deriveTtsState(limpo, { error: 'quota_exceeded' }), 'quota_exceeded');

// shallow inconclusivo sem histórico: assume-se ignorância, não saúde.
assert.strictEqual(
  deriveTtsState(limpo, { error: 'inconclusive' }),
  'unverified',
  'A9: sem prova e sem histórico, o estado é "não verificado" — nunca "pronto"'
);
assert.strictEqual(deriveTtsState(comChave, { error: 'inconclusive' }), 'failing');

// ─── sonda de chave em duas camadas (SPEC-0002, D3/D4) ─────
// Sem rede: o fetch é trocado e conta quantas vezes cada camada foi chamada.
const fetchOriginal = globalThis.fetch;

/**
 * @param {{conta:number|'rede', tts?:number|'rede'}} plano status por camada
 */
function fetchEmCamadas(plano, registro = { conta: 0, tts: 0 }) {
  return async (url) => {
    const camada = String(url).includes('/text-to-speech/') ? 'tts' : 'conta';
    registro[camada] += 1;
    const bruto = plano[camada];
    if (bruto === 'rede') throw new Error('getaddrinfo ENOTFOUND');
    if (bruto === undefined) assert.fail(`camada ${camada} não deveria ser chamada`);
    const status = typeof bruto === 'object' ? bruto.status : bruto;
    const detail = typeof bruto === 'object' ? bruto.type : undefined;
    const res = {
      ok: status >= 200 && status < 300,
      status,
      clone: () => ({ json: async () => ({ detail: { type: detail } }) }),
    };
    return res;
  };
}

try {
  setKey(false);
  resetKeyProbeCache();
  globalThis.fetch = () => assert.fail('não pode ir à rede sem chave');
  const semChave = await verifyApiKey();
  assert.strictEqual(semChave.error, 'elevenlabs_api_key_missing');
  assert.strictEqual(semChave.method, 'none');

  setKey(true);

  // Chave plena: a camada 1 resolve e a camada 2 NÃO roda — não gasta cota (D4).
  resetKeyProbeCache();
  let reg = { conta: 0, tts: 0 };
  globalThis.fetch = fetchEmCamadas({ conta: 200 }, reg);
  const plena = await verifyApiKey();
  assert.strictEqual(plena.ok, true);
  assert.strictEqual(plena.method, 'account');
  assert.strictEqual(reg.tts, 0, 'D4: chave plena não pode gastar cota na sonda');

  // A6 — O CASO REAL: 401 na conta, TTS funciona. É chave boa, não recusada.
  // Sem isto, o diagnóstico reprovaria a chave de produção do Elias.
  for (const statusConta of [401, 403]) {
    resetKeyProbeCache();
    reg = { conta: 0, tts: 0 };
    globalThis.fetch = fetchEmCamadas({ conta: statusConta, tts: 200 }, reg);
    const restrita = await verifyApiKey();
    assert.strictEqual(
      restrita.ok,
      true,
      `A6: ${statusConta} na conta + TTS OK é chave com escopo restrito, não chave morta`
    );
    assert.strictEqual(restrita.method, 'tts');
    assert.strictEqual(reg.tts, 1, 'a camada 2 precisa ter sido consultada');
  }

  // Chave realmente morta: as duas camadas recusam.
  resetKeyProbeCache();
  globalThis.fetch = fetchEmCamadas({ conta: 401, tts: 401 });
  const morta = await verifyApiKey();
  assert.strictEqual(morta.ok, false);
  assert.strictEqual(morta.error, 'key_rejected');
  assert.strictEqual(morta.method, 'tts');

  // Cota estourada não pode ser confundida com chave ruim: o conserto é outro.
  resetKeyProbeCache();
  globalThis.fetch = fetchEmCamadas({ conta: 401, tts: 429 });
  const cota = await verifyApiKey();
  assert.strictEqual(cota.error, 'quota_exceeded');
  assert.notStrictEqual(cota.error, 'key_rejected');

  // Erro de servidor na camada 1 encerra ali — não faz sentido gastar cota.
  resetKeyProbeCache();
  reg = { conta: 0, tts: 0 };
  globalThis.fetch = fetchEmCamadas({ conta: 500 }, reg);
  const erro500 = await verifyApiKey();
  assert.strictEqual(erro500.error, 'http_500');
  assert.strictEqual(reg.tts, 0, '500 não é problema de chave — não sonda TTS');

  // Rede fora não acusa a chave.
  resetKeyProbeCache();
  globalThis.fetch = fetchEmCamadas({ conta: 'rede' });
  assert.strictEqual((await verifyApiKey()).error, 'probe_network_error');

  resetKeyProbeCache();
  globalThis.fetch = async () => {
    const e = new Error('aborted');
    e.name = 'AbortError';
    throw e;
  };
  assert.strictEqual((await verifyApiKey()).error, 'probe_timeout');

  // R2 — nenhum resultado da sonda pode carregar a chave.
  resetKeyProbeCache();
  globalThis.fetch = fetchEmCamadas({ conta: 401, tts: 401 });
  assert.ok(
    !JSON.stringify(await verifyApiKey()).includes('test-marker-not-a-key'),
    'R2: o resultado da sonda não pode conter a chave'
  );

  // Cache: a segunda chamada não vai à rede…
  resetKeyProbeCache();
  let idas = 0;
  globalThis.fetch = async () => {
    idas += 1;
    return { ok: true, status: 200 };
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
  // ── A11 — 400 com authentication_error é chave recusada, não http_400 ──
  // É o status exato do incidente que originou a spec.
  resetKeyProbeCache();
  globalThis.fetch = fetchEmCamadas({ conta: 401, tts: { status: 400, type: 'authentication_error' } });
  const quatrocentos = await verifyApiKey();
  assert.strictEqual(
    quatrocentos.error,
    'key_rejected',
    'A11: 400 com authentication_error é o caso do print — não pode virar http_400'
  );

  // 400 genérico continua sendo 400: não inventamos veredito sobre a chave.
  resetKeyProbeCache();
  globalThis.fetch = fetchEmCamadas({ conta: 401, tts: { status: 400, type: 'invalid_voice_id' } });
  assert.strictEqual((await verifyApiKey()).error, 'http_400');

  // ── A7 — shallow nunca toca o endpoint de TTS (boot não gasta cota) ──
  resetKeyProbeCache();
  reg = { conta: 0, tts: 0 };
  globalThis.fetch = fetchEmCamadas({ conta: 401 }, reg);
  const raso = await verifyApiKey({ depth: 'shallow' });
  assert.strictEqual(raso.error, 'inconclusive', 'shallow não conclui, e diz isso');
  assert.strictEqual(raso.ok, false);
  assert.strictEqual(reg.tts, 0, 'A7: shallow não pode gastar cota');

  // ── A8 — deep sonda a camada 2 mesmo com a conta OK ──
  // É a única forma de detectar cota esgotada em chave de acesso pleno (F3).
  resetKeyProbeCache();
  reg = { conta: 0, tts: 0 };
  globalThis.fetch = fetchEmCamadas({ conta: 200, tts: 429 }, reg);
  const fundo = await verifyApiKey({ depth: 'deep' });
  assert.strictEqual(reg.tts, 1, 'A8: deep precisa provar a síntese');
  assert.strictEqual(
    fundo.error,
    'quota_exceeded',
    'A8: conta OK + síntese recusada por cota — auto diria ready e mentiria'
  );

  // auto, no mesmo cenário, para na camada 1 e não vê a cota. É o custo consciente da D4.
  resetKeyProbeCache();
  reg = { conta: 0, tts: 0 };
  globalThis.fetch = fetchEmCamadas({ conta: 200 }, reg);
  assert.strictEqual((await verifyApiKey({ depth: 'auto' })).ok, true);
  assert.strictEqual(reg.tts, 0);

  // Cache é por profundidade: um shallow inconclusivo não pode servir a um deep.
  resetKeyProbeCache();
  reg = { conta: 0, tts: 0 };
  globalThis.fetch = fetchEmCamadas({ conta: 401, tts: 200 }, reg);
  await verifyApiKeyCached({ depth: 'shallow' });
  const depoisDeep = await verifyApiKeyCached({ depth: 'deep' });
  assert.strictEqual(depoisDeep.cached, false, 'cache não pode cruzar profundidades');
  assert.strictEqual(depoisDeep.ok, true);
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

console.log('✅ tts: estado da chave, classificação, sonda em camadas e profundidade');
