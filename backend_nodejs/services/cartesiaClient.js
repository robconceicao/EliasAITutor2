/**
 * Cartesia REST TTS — secondary provider (SPEC-0001).
 *
 * Complete (non-streaming) synthesis only: the PCM → Opus path already exists and is
 * tested (emitRestTtsAsOpus in server.js), so reusing it halves this delivery. Streaming
 * is explicitly out of scope (SPEC-0001, D2 / não-escopo).
 *
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │ ⚠️  CONTRATO DE REDE NÃO VERIFICADO — SPEC-0001, Q4                          │
 * │                                                                             │
 * │ Os defaults do bloco WIRE abaixo foram escritos sem acesso à documentação   │
 * │ viva do provedor (egress bloqueado no ambiente do ciclo 2). Confirme os      │
 * │ quatro itens contra docs.cartesia.ai ANTES de confiar nisto em produção:     │
 * │   1. caminho do endpoint REST de síntese completa                           │
 * │   2. nome e valor do header de versão da API                                │
 * │   3. id do modelo                                                           │
 * │   4. formato de saída pedido (container/encoding/sample rate) e se a        │
 * │      resposta vem como bytes crus                                           │
 * │ Cada um tem override por env var: corrigir não exige mudar código.          │
 * │ A lógica desta função (chave, erro, PCM, vazamento) é testada offline em     │
 * │ test_tts_failover.js e independe desses valores.                            │
 * └─────────────────────────────────────────────────────────────────────────────┘
 */
import { PROVIDER_CARTESIA, providerHasKey, providerKeySource } from './ttsProvider.js';

// ─── WIRE — ver aviso acima (Q4) ────────────────────────────
const API_URL = () => process.env.CARTESIA_API_URL || 'https://api.cartesia.ai/tts/bytes';
const API_VERSION = () => process.env.CARTESIA_API_VERSION || '2024-06-10';
const MODEL_ID = () => process.env.CARTESIA_MODEL || 'sonic-english';
const SAMPLE_RATE = () => Number(process.env.CARTESIA_SAMPLE_RATE) || 24000;

/**
 * Voz default do código. Q3 decide qual soa mais próxima do General American.
 * Privada: a seção 5.1 da spec não a declara, e superfície não declarada foi o
 * achado F1 do ciclo 1.
 */
const DEFAULT_CARTESIA_VOICE_ID = 'a0e99841-438c-4a64-b679-ae501e7d6091';

/** Máximo de caracteres por requisição — espelha o corte de elevenLabsClient. */
const MAX_CHARS = 2500;

/**
 * D6: o registro é a fonte única da detecção de chave. Este módulo re-exporta em vez
 * de manter a sua própria lista de env vars.
 * @returns {boolean}
 */
export function hasCartesiaKey() {
  return providerHasKey(PROVIDER_CARTESIA);
}

/** @returns {string} id da voz — env `CARTESIA_VOICE_ID`, senão o default do código. */
export function resolveCartesiaVoiceId() {
  const fromEnv = (process.env.CARTESIA_VOICE_ID || '').trim();
  return fromEnv || DEFAULT_CARTESIA_VOICE_ID;
}

/** Chave do provedor. Nunca logue o retorno. */
function apiKey() {
  const envName = providerKeySource(PROVIDER_CARTESIA);
  return envName ? (process.env[envName] || '').trim() : '';
}

/**
 * Síntese completa → PCM Int16 LE.
 * Mesma forma de retorno de elevenLabsClient.synthesizePcmRest(), para que o caminho
 * PCM → Opus do server.js não precise saber de qual provedor veio o áudio (D5).
 *
 * Erros são lançados com mensagem classificável por ttsProvider.isTtsAuthOrQuotaError:
 * status 401/403/429 vira cooldown do provedor; áudio vazio, não (E5).
 *
 * @param {string} text
 * @param {string} [voiceId]
 * @returns {Promise<{ pcm: Buffer, voiceId: string, sampleRate: number }>}
 */
export async function synthesizePcmRest(text, voiceId) {
  const key = apiKey();
  if (!key) throw new Error('cartesia_api_key_missing');

  const trimmed = String(text || '').trim();
  if (!trimmed) throw new Error('empty_tts_text');

  const vid = voiceId || resolveCartesiaVoiceId();
  const sampleRate = SAMPLE_RATE();

  const res = await fetch(API_URL(), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': key,
      'Cartesia-Version': API_VERSION(),
    },
    body: JSON.stringify({
      model_id: MODEL_ID(),
      transcript: trimmed.slice(0, MAX_CHARS),
      voice: { mode: 'id', id: vid },
      output_format: {
        container: 'raw',
        encoding: 'pcm_s16le',
        sample_rate: sampleRate,
      },
      language: 'en',
    }),
  });

  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    // O status entra na mensagem para que isTtsAuthOrQuotaError o reconheça mesmo
    // quando o erro atravessa camadas que só preservam a string.
    const err = new Error(`Cartesia REST TTS ${res.status}: ${detail.slice(0, 200)}`);
    err.status = res.status;
    throw err;
  }

  const pcm = Buffer.from(await res.arrayBuffer());
  if (pcm.length < 100) throw new Error('Cartesia REST returned empty audio');

  console.log(`[cartesia] REST TTS ok voice=${vid} bytes=${pcm.length} rate=${sampleRate}`);
  return { pcm, voiceId: vid, sampleRate };
}
