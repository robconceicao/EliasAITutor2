const TADEU_APPS_URL = (
  process.env.TADEU_APPS_URL || 'https://tadeu-apps-core-test2.vercel.app'
).replace(/\/$/, '');

const TADEU_LICENSE_ENFORCED = ['1', 'true', 'yes', 'on'].includes(
  String(process.env.TADEU_LICENSE_ENFORCED || 'false').toLowerCase()
);

export const ELIAS_VOICE_FEATURE = 'voice_minutes_monthly';

export class TadeuMeteringError extends Error {
  constructor(code, status, message, details = {}) {
    super(message);
    this.name = 'TadeuMeteringError';
    this.code = code;
    this.status = status;
    this.details = details;
  }
}

function requireToken(token) {
  if (token) return true;
  if (TADEU_LICENSE_ENFORCED) {
    throw new TadeuMeteringError(
      'tadeu_license_required',
      403,
      'Ative sua licença Tadeu Apps para continuar.'
    );
  }
  console.warn('[TADEU] token ausente; validação ignorada em modo de transição');
  return false;
}

async function parseJson(response) {
  try {
    return await response.json();
  } catch {
    return {};
  }
}

function throwForResponse(response, data, feature) {
  if (response.status === 429) {
    throw new TadeuMeteringError(
      'monthly_limit_exceeded',
      429,
      'Você atingiu o limite mensal de voz do seu plano.',
      {
        feature,
        used: data.used ?? null,
        limit: data.limit ?? null,
        remaining: data.remaining ?? null,
      }
    );
  }

  if (response.status === 401 || response.status === 403) {
    throw new TadeuMeteringError(
      'tadeu_license_denied',
      403,
      'Sua licença Tadeu Apps não permite esta operação.',
      { feature }
    );
  }
}

function unavailable(error, operation) {
  console.error(`[TADEU] ${operation} indisponível:`, error?.message || error);
  if (TADEU_LICENSE_ENFORCED) {
    throw new TadeuMeteringError(
      'tadeu_metering_unavailable',
      503,
      'Não foi possível validar sua cota agora. Tente novamente.'
    );
  }
  return null;
}

export async function checkTadeuQuota(token, feature = ELIAS_VOICE_FEATURE) {
  if (!requireToken(token)) return null;

  let response;
  try {
    const url = new URL(`${TADEU_APPS_URL}/api/apps/elias-ai-tutor/usage`);
    url.searchParams.set('feature', feature);
    response = await fetch(url, {
      headers: { Authorization: `Bearer ${token}` },
    });
  } catch (error) {
    return unavailable(error, 'quota');
  }

  const data = await parseJson(response);
  throwForResponse(response, data, feature);

  if (!response.ok) {
    return unavailable(new Error(`HTTP ${response.status}`), 'quota');
  }

  if (
    data.limit !== null &&
    data.limit !== undefined &&
    data.remaining !== null &&
    data.remaining !== undefined &&
    Number(data.remaining) <= 0
  ) {
    throw new TadeuMeteringError(
      'monthly_limit_exceeded',
      429,
      'Você atingiu o limite mensal de voz do seu plano.',
      {
        feature,
        used: data.used ?? null,
        limit: data.limit,
        remaining: data.remaining,
      }
    );
  }

  return data;
}

export async function consumeTadeuUsage({
  token,
  feature = ELIAS_VOICE_FEATURE,
  amount = 1,
  idempotencyKey = null,
}) {
  if (!requireToken(token)) return null;

  let response;
  try {
    response = await fetch(`${TADEU_APPS_URL}/api/apps/elias-ai-tutor/usage`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        feature,
        amount,
        ...(idempotencyKey ? { idempotencyKey: String(idempotencyKey).slice(0, 200) } : {}),
      }),
    });
  } catch (error) {
    return unavailable(error, 'consumo');
  }

  const data = await parseJson(response);
  throwForResponse(response, data, feature);

  if (!response.ok) {
    return unavailable(new Error(`HTTP ${response.status}`), 'consumo');
  }

  return data;
}

/**
 * Acumulador de fala por sessão.
 *
 * Não faz arredondamento antecipado: somente cada 60.000 ms completos geram
 * uma unidade de consumo. O resto permanece para o próximo turno.
 * A chave de idempotência é estável por minuto cruzado.
 */
export class VoiceMinuteAccumulator {
  constructor(sessionId) {
    this.sessionId = sessionId || `voice_${Date.now()}`;
    this.totalSpeechMs = 0;
    this.consumedMinutes = 0;
  }

  addSpeech(durationMs) {
    const safeMs = Math.max(0, Math.floor(Number(durationMs) || 0));
    this.totalSpeechMs += safeMs;
    const completedMinutes = Math.floor(this.totalSpeechMs / 60_000);
    const newlyCompleted = Math.max(0, completedMinutes - this.consumedMinutes);
    return {
      newlyCompleted,
      completedMinutes,
      totalSpeechMs: this.totalSpeechMs,
      remainderMs: this.totalSpeechMs % 60_000,
    };
  }

  markConsumed(count) {
    const safe = Math.max(0, Math.floor(Number(count) || 0));
    this.consumedMinutes += safe;
  }

  idempotencyKey(minuteNumber) {
    return `voice:${this.sessionId}:minute:${minuteNumber}`;
  }
}
