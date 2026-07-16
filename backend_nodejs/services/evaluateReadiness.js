/**
 * B.2 — Adaptive tutor readiness gate (isolated pure logic).
 * All three signals must pass (logical AND). Swap to 2-of-3 here only if product changes.
 *
 * CEFR comparison uses an explicit ordinal map (never raw string compare):
 *   A1 < A2 < B1 < B2 < C1 < C2
 */

/** Explicit ordinal list — do not replace with locale/string ordering. */
const CEFR_RANK = {
  A1: 1,
  A2: 2,
  B1: 3,
  B2: 4,
  C1: 5,
  C2: 6,
};

export function normalizeCefr(level) {
  if (!level || typeof level !== 'string') return '';
  const m = level.trim().toUpperCase().match(/A1|A2|B1|B2|C1|C2/);
  return m ? m[0] : '';
}

export function cefrRank(level) {
  return CEFR_RANK[normalizeCefr(level)] || 0;
}

/** True if estimate is equal or above required (A1 < A2 < B1 < B2 < C1). */
export function cefrAtLeast(estimate, required) {
  const e = cefrRank(estimate);
  const r = cefrRank(required);
  if (!r) return true; // no expected level → do not block
  if (!e) return false;
  return e >= r;
}

/**
 * Best (highest) CEFR among session estimates — useful for display only.
 */
export function bestCefrEstimate(estimates) {
  let best = null;
  let bestRank = 0;
  for (const raw of estimates || []) {
    const n = normalizeCefr(raw);
    const r = cefrRank(n);
    if (r > bestRank) {
      bestRank = r;
      best = n;
    }
  }
  return best;
}

/**
 * Conservative CEFR for mastery gate: lowest valid estimate of the week
 * (stricter than "best session wins"). Empty → null (not ready).
 */
export function conservativeCefrEstimate(estimates) {
  let worst = null;
  let worstRank = Infinity;
  for (const raw of estimates || []) {
    const n = normalizeCefr(raw);
    const r = cefrRank(n);
    if (r > 0 && r < worstRank) {
      worstRank = r;
      worst = n;
    }
  }
  return worst;
}

/**
 * Count mistakes with severity === 'critical' across feedback objects.
 * Unknown severity is treated as 'minor' (backward compatible with old reports).
 */
export function countCriticalMistakes(feedbackList) {
  let n = 0;
  for (const fb of feedbackList || []) {
    const mistakes = fb?.mistakes || [];
    for (const m of mistakes) {
      if (String(m?.severity || 'minor').toLowerCase() === 'critical') n += 1;
    }
  }
  return n;
}

/**
 * @param {object} input
 * @param {number|null} input.quizScorePercent
 * @param {number} [input.passingScorePercent=70]
 * @param {string[]} input.cefrEstimates — from week sessions' feedback_json.cefr_estimate
 * @param {string} input.expectedLevel — program_weeks.level for current week
 * @param {object[]} input.feedbackList — feedback_json objects for the week
 * @param {number} [input.maxCriticalMistakes=2]
 * @returns {{ ready: boolean, reasons: string[], details: object }}
 */
export function evaluateReadiness({
  quizScorePercent,
  passingScorePercent = 70,
  cefrEstimates = [],
  expectedLevel = '',
  feedbackList = [],
  maxCriticalMistakes = 2,
} = {}) {
  const reasons = [];
  const passMark = Number(passingScorePercent) || 70;
  const score =
    quizScorePercent == null || Number.isNaN(Number(quizScorePercent))
      ? null
      : Number(quizScorePercent);

  const quizOk = score != null && score >= passMark;
  if (score == null) {
    reasons.push('Quiz semanal ainda não foi feito ou não tem nota.');
  } else if (!quizOk) {
    reasons.push(
      `Quiz: ${score}% (mínimo ${passMark}%).`
    );
  }

  // Mastery gate uses conservative (lowest) CEFR — not the best session spike
  const gateCefr = conservativeCefrEstimate(cefrEstimates);
  const cefrOk = gateCefr != null && cefrAtLeast(gateCefr, expectedLevel);
  if (gateCefr == null) {
    reasons.push('Sem estimativa CEFR nas sessões da semana (pratique e encerre sessões com relatório).');
  } else if (!cefrOk) {
    reasons.push(
      `CEFR estimado ${gateCefr} (mais baixo da semana) abaixo do nível da semana ${normalizeCefr(expectedLevel) || expectedLevel}.`
    );
  }

  const criticalCount = countCriticalMistakes(feedbackList);
  const criticalOk = criticalCount <= maxCriticalMistakes;
  if (!criticalOk) {
    reasons.push(
      `Erros críticos: ${criticalCount} (máximo ${maxCriticalMistakes}).`
    );
  }

  const ready = quizOk && cefrOk && criticalOk;

  return {
    ready,
    reasons: ready ? [] : reasons,
    details: {
      quiz_score_percent: score,
      passing_score_percent: passMark,
      quiz_ok: quizOk,
      best_cefr: bestCefrEstimate(cefrEstimates),
      gate_cefr: gateCefr,
      expected_level: normalizeCefr(expectedLevel) || expectedLevel,
      cefr_ok: cefrOk,
      critical_mistakes: criticalCount,
      max_critical_mistakes: maxCriticalMistakes,
      critical_ok: criticalOk,
    },
  };
}

/**
 * Build deficient_topics list for review card (B.4).
 */
export function buildDeficientTopics({
  weekTitle = '',
  wrongQuestionHints = [],
  feedbackList = [],
} = {}) {
  const topics = [];
  const seen = new Set();

  const push = (t) => {
    const s = String(t || '').trim();
    if (!s) return;
    const key = s.toLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    topics.push(s);
  };

  for (const h of wrongQuestionHints) push(h);
  if (wrongQuestionHints.length === 0 && weekTitle) {
    push(`Revisar: ${weekTitle}`);
  }

  for (const fb of feedbackList || []) {
    for (const m of fb?.mistakes || []) {
      if (String(m?.severity || '').toLowerCase() === 'critical') {
        push(m.note || `${m.said || '?'} → ${m.correct || '?'}`);
      }
    }
  }

  // non-critical mistakes as secondary hints (cap)
  for (const fb of feedbackList || []) {
    for (const m of fb?.mistakes || []) {
      if (String(m?.severity || 'minor').toLowerCase() !== 'critical') {
        push(m.note || m.correct || '');
      }
      if (topics.length >= 12) break;
    }
  }

  const last = (feedbackList || []).filter(Boolean).at(-1);
  if (last?.next_focus) push(`Próximo foco: ${last.next_focus}`);

  return topics.slice(0, 12);
}
