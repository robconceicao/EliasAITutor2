/**
 * Testes unitários do nivelamento + progressão de semana ancorada em start_week.
 * Rodar: node test_placement.js
 */
import assert from 'assert';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import {
  buildPlacementQuestions,
  placementForClient,
  scorePlacement,
  beginnerPlacement,
  PLACEMENT_TIERS,
  LEVEL_WEEK_RANGES,
} from './services/placementService.js';
import {
  computeEffectiveWeek,
  unlockedWeek,
  resolveWeek,
  startWeekOf,
} from './services/programStore.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const quizSeed = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'seeds', 'elias_quiz_seed.json'), 'utf8')
);
const quizByWeek = new Map(quizSeed.weeks.map((w) => [Number(w.week), w]));
const getQuiz = async (w) => quizByWeek.get(Number(w)) || null;

// ── 1. Faixas de nível batem com o currículo semeado ───────────
const curriculum = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'seeds', 'elias_curriculum_seed.json'), 'utf8')
);
for (const [level, range] of Object.entries(LEVEL_WEEK_RANGES)) {
  const weeks = curriculum.weeks.filter((w) => w.level === level).map((w) => w.week);
  assert.strictEqual(Math.min(...weeks), range.first, `primeira semana de ${level}`);
  assert.strictEqual(Math.max(...weeks), range.last, `última semana de ${level}`);
}

// ── 2. Montagem do teste ───────────────────────────────────────
const questions = await buildPlacementQuestions(getQuiz);
assert.strictEqual(questions.length, 20, 'nivelamento deve ter 20 questões');
for (let t = 1; t <= 5; t++) {
  assert.strictEqual(
    questions.filter((q) => q.tier === t).length,
    4,
    `tier ${t} deve ter 4 questões`
  );
}
const publicPayload = placementForClient(questions);
const leaked = JSON.stringify(publicPayload).includes('correct_index');
assert.ok(!leaked, 'payload público não pode vazar gabarito');
assert.strictEqual(publicPayload.questions.length, 20);

// Helpers de resposta
const allCorrect = questions.map((q) => q.correct_index);
const wrongFor = (q) => (q.correct_index + 1) % Math.max(2, q.options.length);
const answersUpToTier = (maxTier) =>
  questions.map((q) => (q.tier <= maxTier ? q.correct_index : wrongFor(q)));

// ── 3. Pontuação → semana inicial ──────────────────────────────
const zero = scorePlacement(questions, questions.map(wrongFor));
assert.strictEqual(zero.start_week, 1, 'errando tudo → Semana 1');
assert.strictEqual(zero.level, 'A1');

const t1 = scorePlacement(questions, answersUpToTier(1));
assert.strictEqual(t1.start_week, 5, 'domina A1 → Semana 5 (A2)');
assert.strictEqual(t1.level, 'A1');

const t2 = scorePlacement(questions, answersUpToTier(2));
assert.strictEqual(t2.start_week, 9, 'domina A2 → Semana 9 (B1)');

const t3 = scorePlacement(questions, answersUpToTier(3));
assert.strictEqual(t3.start_week, 15, 'domina B1 → Semana 15 (B2)');

const t4 = scorePlacement(questions, answersUpToTier(4));
assert.strictEqual(t4.start_week, 22, 'domina B2 → Semana 22 (C1)');

const perfect = scorePlacement(questions, allCorrect);
assert.strictEqual(perfect.start_week, 22, 'domina tudo → fase final, nunca >22');
assert.strictEqual(perfect.score_percent, 100);
assert.strictEqual(perfect.level, 'C1');

// Monotonicidade: acerto isolado num nível alto não pula a base
const luckyC1 = questions.map((q) =>
  q.tier === 5 ? q.correct_index : wrongFor(q)
);
const lucky = scorePlacement(questions, luckyC1);
assert.strictEqual(lucky.start_week, 1, 'acertar só C1 não pula a fundação');

// Iniciante declarado
const beginner = beginnerPlacement();
assert.strictEqual(beginner.start_week, 1);
assert.strictEqual(beginner.level, 'A1');

// ── 4. Calendário ancorado na semana inicial ───────────────────
assert.strictEqual(
  computeEffectiveWeek('2026-01-01', '2026-01-01', 0, 9),
  9,
  'dia 1 com start_week=9 → Semana 9'
);
assert.strictEqual(
  computeEffectiveWeek('2026-01-01', '2026-01-08', 0, 9),
  10,
  'após 7 dias → Semana 10'
);
assert.strictEqual(
  computeEffectiveWeek('2026-01-01', '2026-01-07', 0, 9),
  9,
  'dia 7 ainda é a mesma semana (borda)'
);
assert.strictEqual(
  computeEffectiveWeek('2026-01-01', '2026-01-15', 7, 9),
  10,
  '7 dias pausados descontam uma semana'
);
assert.strictEqual(
  computeEffectiveWeek('2026-01-01', '2030-01-01', 0, 9),
  26,
  'nunca passa de 26'
);
assert.strictEqual(
  computeEffectiveWeek('2026-01-01', '2026-01-01', 0, 1),
  1,
  'sem nivelamento continua igual ao comportamento antigo'
);

// ── 5. Gate de quiz respeita a semana inicial ──────────────────
const placed = {
  start_date: '2026-01-01',
  start_week: 9,
  mastery_cleared_week: 8,
  week_mode: 'auto',
  held_back: false,
  total_paused_days: 0,
  current_week: 9,
};
assert.strictEqual(startWeekOf(placed), 9);
assert.strictEqual(unlockedWeek(placed), 9, 'nivelado em 9 → semana 9 liberada');
assert.ok(resolveWeek(placed) >= 9, 'nunca cai abaixo da semana do nivelamento');

// manual não pode voltar antes do nivelamento nem pular o gate
assert.strictEqual(
  resolveWeek({ ...placed, week_mode: 'manual', current_week: 3 }),
  9,
  'manual não volta antes da semana inicial'
);
assert.strictEqual(
  resolveWeek({ ...placed, week_mode: 'manual', current_week: 20 }),
  9,
  'manual não pula semana bloqueada pelo quiz'
);

console.log('✅ placement + progressão por semana inicial: todos os testes passaram');
console.log(
  `   tiers: ${PLACEMENT_TIERS.map((t) => `${t.level}→S${t.startWeek}`).join(' ')}`
);
