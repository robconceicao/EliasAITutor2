/**
 * Teste de nivelamento (placement) — define a SEMANA INICIAL do programa.
 *
 * Motivação: o início do programa não é fixo na Semana 1. Um aluno que já
 * fala A2/B1 perderia semanas repetindo conteúdo que já domina, e o gate de
 * mastery (quiz por semana) travaria o avanço mesmo com o calendário correndo.
 *
 * Estratégia: reaproveitar 100% do banco de quizzes já semeado
 * (seeds/elias_quiz_seed.json). Nenhuma dependência nova, nenhum conteúdo novo.
 *
 * 5 níveis (tiers) × 4 questões = 20 questões, cobrindo A1 → C1.
 * O aluno começa na primeira semana do PRIMEIRO nível que ainda NÃO domina.
 */

/** Faixas de semanas por nível CEFR no currículo semeado (26 semanas). */
export const LEVEL_WEEK_RANGES = {
  A1: { first: 1, last: 4 },
  A2: { first: 5, last: 8 },
  B1: { first: 9, last: 14 },
  B2: { first: 15, last: 21 },
  C1: { first: 22, last: 26 },
};

/**
 * Tiers do nivelamento. Cada tier puxa 2 questões de 2 semanas do próprio nível
 * (índices fixos → teste determinístico e reproduzível).
 */
export const PLACEMENT_TIERS = [
  { level: 'A1', startWeek: 1, sources: [{ week: 2, indices: [0, 5] }, { week: 4, indices: [1, 6] }] },
  { level: 'A2', startWeek: 5, sources: [{ week: 6, indices: [0, 5] }, { week: 8, indices: [1, 6] }] },
  { level: 'B1', startWeek: 9, sources: [{ week: 11, indices: [0, 5] }, { week: 13, indices: [1, 6] }] },
  { level: 'B2', startWeek: 15, sources: [{ week: 17, indices: [0, 5] }, { week: 20, indices: [1, 6] }] },
  { level: 'C1', startWeek: 22, sources: [{ week: 23, indices: [0, 5] }, { week: 25, indices: [1, 6] }] },
];

/** Um tier é considerado dominado com pelo menos esta fração de acertos. */
export const TIER_PASS_RATIO = 0.75;

/**
 * Monta as questões do nivelamento a partir dos quizzes semanais.
 * @param {(week:number)=>Promise<object|null>} getQuizFn
 * @returns {Promise<Array<{tier:number, level:string, source_week:number,
 *   question:string, options:string[], correct_index:number}>>}
 */
export async function buildPlacementQuestions(getQuizFn) {
  const out = [];
  for (let t = 0; t < PLACEMENT_TIERS.length; t++) {
    const tier = PLACEMENT_TIERS[t];
    for (const src of tier.sources) {
      const quiz = await getQuizFn(src.week);
      const bank = quiz?.questions || [];
      if (!bank.length) continue;
      for (const rawIdx of src.indices) {
        // Índice seguro: se o banco for menor que o esperado, dá a volta.
        const q = bank[rawIdx % bank.length];
        if (!q) continue;
        out.push({
          tier: t + 1,
          level: tier.level,
          source_week: src.week,
          question: q.question,
          options: q.options || [],
          correct_index: Number(q.correct_index),
        });
      }
    }
  }
  return out;
}

/** Versão pública: sem gabarito, para enviar ao app. */
export function placementForClient(questions) {
  return {
    total: questions.length,
    tier_pass_ratio: TIER_PASS_RATIO,
    tiers: PLACEMENT_TIERS.map((t, i) => ({
      tier: i + 1,
      level: t.level,
      start_week: t.startWeek,
    })),
    questions: questions.map((q) => ({
      tier: q.tier,
      level: q.level,
      question: q.question,
      options: q.options,
    })),
  };
}

/**
 * Corrige o nivelamento e resolve a semana inicial.
 *
 * Regra (conservadora e monotônica): o aluno "domina" até o tier T se acertou
 * ≥75% de TODOS os tiers de 1 a T. Ele começa na primeira semana do tier T+1.
 * Assim um acerto isolado num nível alto não pula conteúdo de base.
 *
 * @param {Array} questions saída de buildPlacementQuestions()
 * @param {number[]} answers índices escolhidos, mesma ordem das questões
 */
export function scorePlacement(questions, answers) {
  const ans = Array.isArray(answers) ? answers : [];
  const tiers = PLACEMENT_TIERS.map((t, i) => ({
    tier: i + 1,
    level: t.level,
    start_week: t.startWeek,
    correct: 0,
    total: 0,
    passed: false,
  }));

  let correctTotal = 0;
  const details = [];

  for (let i = 0; i < questions.length; i++) {
    const q = questions[i];
    const slot = tiers[q.tier - 1];
    if (!slot) continue;
    slot.total += 1;
    const chosen = ans[i];
    const ok = chosen !== undefined && chosen !== null && Number(chosen) === q.correct_index;
    if (ok) {
      slot.correct += 1;
      correctTotal += 1;
    }
    details.push({
      index: i,
      tier: q.tier,
      level: q.level,
      chosen_index: chosen === undefined || chosen === null ? null : Number(chosen),
      correct_index: q.correct_index,
      is_correct: ok,
    });
  }

  for (const t of tiers) {
    t.passed = t.total > 0 && t.correct / t.total >= TIER_PASS_RATIO;
  }

  // Maior T tal que os tiers 1..T foram todos dominados.
  let cleared = 0;
  for (const t of tiers) {
    if (t.passed) cleared = t.tier;
    else break;
  }

  const nextTier = PLACEMENT_TIERS[cleared]; // undefined se dominou todos
  const startWeek = nextTier
    ? nextTier.startWeek
    : PLACEMENT_TIERS[PLACEMENT_TIERS.length - 1].startWeek; // domina tudo → última fase

  const level = cleared === 0 ? 'A1' : PLACEMENT_TIERS[cleared - 1].level;
  const totalQuestions = questions.length || 1;

  return {
    start_week: Math.min(26, Math.max(1, startWeek)),
    level,
    cleared_tier: cleared,
    score_percent: Math.round((correctTotal / totalQuestions) * 100),
    correct_count: correctTotal,
    total: questions.length,
    tiers,
    details,
    summary:
      cleared === 0
        ? 'Base A1 ainda em construção — você começa na Semana 1, do zero, do jeito certo.'
        : cleared >= PLACEMENT_TIERS.length
          ? `Nível ${level} confirmado — você entra direto na fase final (Semana ${startWeek}).`
          : `Nível ${level} dominado — você pula direto para a Semana ${startWeek} (${nextTier.level}).`,
  };
}

/**
 * Atalho sem prova: o aluno declara que nunca estudou / quer começar do zero.
 */
export function beginnerPlacement() {
  return {
    start_week: 1,
    level: 'A1',
    cleared_tier: 0,
    score_percent: 0,
    correct_count: 0,
    total: 0,
    tiers: [],
    details: [],
    summary: 'Início do zero — Semana 1, fase de Fundação.',
  };
}
