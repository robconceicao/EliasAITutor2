/**
 * Testa o caminho MongoDB do Modo Programa SEM um servidor Mongo.
 *
 * Por que isto existe: o projeto sempre rodou com `MONGODB_URI` ausente, então
 * o ramo `mongoEnabled === true` do programStore nunca foi exercitado. Ao ligar
 * o Mongo pela primeira vez, qualquer campo que o store grava e o schema não
 * aceita vira erro de validação em produção — no meio de uma sessão de estudo.
 *
 * Estratégia: substituir os métodos estáticos dos models por fakes em memória
 * que validam CADA documento contra o schema Mongoose real
 * (`new Model(doc).validateSync()`). Assim pegamos incompatibilidade de schema,
 * enum inválido, campo obrigatório faltando e violação de min/max.
 *
 * Rodar: node test_mongo_path.js
 */
import assert from 'assert';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import {
  ProgramWeek,
  UserProgramState,
  PracticeSession,
  ProgramQuiz,
} from './models/programModels.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ── Fake store validado pelo schema real ───────────────────────
const collections = new Map();
const validationErrors = [];

function keyOf(Model) {
  return Model.modelName;
}

function store(Model) {
  const k = keyOf(Model);
  if (!collections.has(k)) collections.set(k, []);
  return collections.get(k);
}

function matches(doc, filter) {
  return Object.entries(filter || {}).every(([k, v]) => String(doc[k]) === String(v));
}

/**
 * Passa o documento pelo schema real e devolve o que o Mongo REALMENTE gravaria.
 *
 * Detalhe crítico: no modo strict (default), o Mongoose **descarta em silêncio**
 * campos que o schema não declara — não dá erro. Então validar não basta: é
 * preciso comparar o que entrou com o que sobrou. Um campo novo esquecido no
 * schema (ex.: `start_week`) sumiria sem nenhum aviso, e o nivelamento do aluno
 * evaporaria no primeiro restart.
 */
function throughSchema(Model, doc, context) {
  try {
    const instance = new Model(doc);
    const err = instance.validateSync();
    if (err) {
      validationErrors.push(`${keyOf(Model)} (${context}): ${err.message}`);
    }
    const persisted = instance.toObject({ depopulate: true, flattenMaps: true });

    // Campos que entraram mas o schema jogou fora.
    // Exceção conhecida do Mongoose: valor Mixed vazio ({} ou []) não é
    // serializado. Isso é inofensivo aqui porque normalizeState() reaplica o
    // default na leitura — mas perder um valor NÃO vazio seria perda de dados.
    for (const k of Object.keys(doc)) {
      const v = doc[k];
      if (v === undefined) continue;
      if (k in persisted) continue;
      const emptyMixed =
        v !== null &&
        typeof v === 'object' &&
        (Array.isArray(v) ? v.length === 0 : Object.keys(v).length === 0);
      if (emptyMixed) continue;
      validationErrors.push(
        `${keyOf(Model)} (${context}): campo "${k}" foi DESCARTADO ` +
          `(valor: ${JSON.stringify(v).slice(0, 60)}) — falta no schema`
      );
    }
    delete persisted._id;
    delete persisted.__v;
    return persisted;
  } catch (e) {
    validationErrors.push(`${keyOf(Model)} (${context}) exceção: ${e.message}`);
    return doc;
  }
}

function installFakes(Model) {
  Model.findOneAndUpdate = async (filter, update, _opts) => {
    const rows = store(Model);
    const idx = rows.findIndex((r) => matches(r, filter));
    const base = idx >= 0 ? rows[idx] : { ...filter };
    // O store passa o documento inteiro (sem operadores $), como no código real.
    const merged = throughSchema(Model, { ...base, ...update }, 'findOneAndUpdate');
    if (idx >= 0) rows[idx] = merged;
    else rows.push(merged);
    return merged;
  };
  Model.create = async (doc) => {
    store(Model).push(throughSchema(Model, doc, 'create'));
    return doc;
  };
  Model.findOne = (filter) => ({
    lean: async () => store(Model).find((r) => matches(r, filter)) || null,
  });
  Model.find = (filter) => {
    const run = () => store(Model).filter((r) => matches(r, filter || {}));
    const chain = {
      sort: () => chain,
      lean: async () => run(),
    };
    return chain;
  };
}

[ProgramWeek, UserProgramState, PracticeSession, ProgramQuiz].forEach(installFakes);

// ── Store em modo Mongo ────────────────────────────────────────
const {
  setMongoEnabled,
  upsertWeeks,
  upsertQuizzes,
  getWeek,
  getAllWeeks,
  getProgramState,
  updateProgramState,
  applyPlacement,
  submitQuizAnswers,
  createSession,
  endSession,
  updateSessionFeedback,
  getProgressSummary,
  runCheckpoint,
} = await import('./services/programStore.js');

setMongoEnabled(true);

// ── 1. Seed do currículo e dos quizzes ─────────────────────────
const curriculum = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'seeds', 'elias_curriculum_seed.json'), 'utf8')
);
const quizSeed = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'seeds', 'elias_quiz_seed.json'), 'utf8')
);

await upsertWeeks(curriculum.weeks);
await upsertQuizzes(quizSeed);

const allWeeks = await getAllWeeks();
assert.strictEqual(allWeeks.length, 26, 'as 26 semanas devem persistir no Mongo');

// Idempotência: rodar o seed de novo não duplica
await upsertWeeks(curriculum.weeks);
assert.strictEqual(
  store(ProgramWeek).length,
  26,
  `seed reexecutado duplicou linhas (${store(ProgramWeek).length})`
);

const w12 = await getWeek(12);
assert.ok(w12 && w12.week === 12, 'leitura de semana pelo Mongo');
assert.strictEqual(w12.chunks.length, 10, 'chunks preservados no schema');
assert.strictEqual(w12.anki_sentences.length, 8, 'frases Anki preservadas');

// ── 2. Estado inicial ──────────────────────────────────────────
const initial = await getProgramState();
assert.strictEqual(initial.current_week, 1);
assert.strictEqual(initial.placement_done, false);
assert.strictEqual(initial.start_week, 1);

// ── 3. Nivelamento grava campos novos sem quebrar o schema ─────
const placed = await applyPlacement({
  start_week: 9,
  level: 'B1',
  score_percent: 82,
});
assert.strictEqual(placed.start_week, 9, 'start_week persistido no Mongo');
assert.strictEqual(placed.current_week, 9, 'semana inicial aplicada');
assert.strictEqual(placed.placement_done, true);
assert.strictEqual(placed.placement_level, 'B1');
assert.strictEqual(placed.mastery_cleared_week, 8, 'semanas anteriores liberadas');

// Releitura vinda do "banco" mantém tudo
const reread = await getProgramState();
assert.strictEqual(reread.start_week, 9);
assert.strictEqual(reread.placement_level, 'B1');
assert.strictEqual(reread.placement_score, 82);

// ── 4. Quiz: score composto e desbloqueio ──────────────────────
const quiz9 = quizSeed.weeks.find((w) => w.week === 9);
const perfect = quiz9.questions.map((q) => Number(q.correct_index));
const result = await submitQuizAnswers(9, perfect);
assert.strictEqual(result.passed, true, 'gabarito completo deve passar');
assert.strictEqual(result.unlocked_week, 10, 'passar na 9 libera a 10');

const afterQuiz = await getProgramState();
assert.strictEqual(
  afterQuiz.mastery_cleared_week,
  9,
  'mastery_cleared_week gravado no Mongo'
);
assert.ok(
  afterQuiz.quiz_scores && afterQuiz.quiz_scores['9'],
  'quiz_scores (campo Mixed) sobreviveu ao schema'
);
assert.strictEqual(afterQuiz.quiz_scores['9'].passed, true);

// Reprovar não deve rebaixar o que já foi liberado
const wrong = quiz9.questions.map((q) => (Number(q.correct_index) + 1) % 3);
const failed = await submitQuizAnswers(9, wrong);
assert.strictEqual(failed.passed, false);
const afterFail = await getProgramState();
assert.strictEqual(
  afterFail.mastery_cleared_week,
  9,
  'reprovar não remove semana já dominada'
);

// ── 5. Sessões de prática ──────────────────────────────────────
const { id: sid } = await createSession({
  week: 9,
  type: 'themed',
  started_at: new Date().toISOString(),
});
assert.ok(sid, 'sessão criada no Mongo');

await endSession(sid, {
  ended_at: new Date().toISOString(),
  duration_seconds: 1920, // 32 min
});

// feedback_status precisa respeitar o enum do schema
await updateSessionFeedback(
  sid,
  {
    strengths: ['Boa fluência'],
    mistakes: [{ said: 'I go yesterday', correct: 'I went yesterday', note: 'passado' }],
    cefr_estimate: 'B1',
    next_focus: 'Past simple',
  },
  'ready'
);

const drill = await createSession({ week: 9, type: 'chunks' });
await endSession(drill.id, { ended_at: new Date().toISOString(), duration_seconds: 300 });

const summary = await getProgressSummary(30);
assert.strictEqual(
  summary.today_minutes,
  37,
  `32 min + 5 min = 37 min no dia (got ${summary.today_minutes})`
);
assert.strictEqual(summary.streak, 1, 'streak conta o dia de hoje');
assert.strictEqual(summary.current_week, 9);
assert.strictEqual(summary.phase, 2, 'semana 9 é fase 2');

// Tipo inválido continua rejeitado
await assert.rejects(
  () => createSession({ week: 9, type: 'invalido' }),
  /type must be/,
  'tipo de sessão inválido deve ser rejeitado antes do Mongo'
);

// ── 6. Checkpoint grava held_back / deficient_topics ───────────
const checkpoint = await runCheckpoint();
assert.ok(typeof checkpoint.ready === 'boolean', 'checkpoint executa sob Mongo');
const afterCheckpoint = await getProgramState();
assert.ok(
  afterCheckpoint.deficient_topics === null ||
    Array.isArray(afterCheckpoint.deficient_topics),
  'deficient_topics (Mixed) válido'
);

// ── 7. Semana no limite superior do schema (min/max 1..26) ─────
const late = await updateProgramState({
  start_week: 26,
  current_week: 26,
  mastery_cleared_week: 25,
  week_mode: 'manual',
});
assert.strictEqual(late.current_week, 26, 'semana 26 aceita pelo schema');

// ── Resultado ──────────────────────────────────────────────────
if (validationErrors.length) {
  console.error('❌ Documentos rejeitados pelo schema Mongoose:');
  for (const e of validationErrors) console.error('   -', e);
  process.exit(1);
}

console.log('✅ caminho MongoDB validado contra os schemas reais');
console.log(
  `   coleções: ${[...collections.entries()]
    .map(([k, v]) => `${k}=${v.length}`)
    .join(' · ')}`
);
