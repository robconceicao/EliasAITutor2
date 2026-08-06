/**
 * Program data store: MongoDB when available, in-memory fallback otherwise.
 */
import { randomUUID } from 'crypto';
import {
  ProgramWeek,
  UserProgramState,
  PracticeSession,
  ProgramQuiz,
} from '../models/programModels.js';
import {
  readSnapshot,
  writeSnapshot,
  setFileStoreEnabled,
} from './stateFileStore.js';

/** @type {Map<number, object>} */
const memoryWeeks = new Map();
/** @type {object|null} */
let memoryState = null;
/** @type {Map<string, object>} */
const memorySessions = new Map();
/** @type {Map<number, object>} week → quiz doc */
const memoryQuizzes = new Map();

let mongoEnabled = false;

/** Snapshot em arquivo já restaurado nesta execução? */
let snapshotRestored = false;

export function setMongoEnabled(flag) {
  mongoEnabled = !!flag;
  // Com Mongo ativo, ele é a fonte de verdade — o arquivo vira ruído.
  setFileStoreEnabled(!mongoEnabled);
}

export function isMongoEnabled() {
  return mongoEnabled;
}

/**
 * Restaura estado + sessões do arquivo (uma vez por processo).
 * Chamado preguiçosamente na primeira leitura de estado.
 */
function restoreFromFileOnce() {
  if (snapshotRestored || mongoEnabled) return;
  snapshotRestored = true;
  const snap = readSnapshot();
  if (!snap) return;
  if (snap.state) memoryState = snap.state;
  for (const s of snap.sessions || []) {
    if (s?.id) memorySessions.set(s.id, s);
  }
  console.log(
    `💾 Estado do programa restaurado do disco (semana ${snap.state?.current_week ?? '?'}, ${
      (snap.sessions || []).length
    } sessões)`
  );
}

/** Persiste estado + sessões no arquivo (no-op quando Mongo está ativo). */
function persistSnapshot() {
  if (mongoEnabled) return;
  writeSnapshot({
    state: memoryState,
    sessions: Array.from(memorySessions.values()),
  });
}

function todayISO() {
  return new Date().toISOString().slice(0, 10);
}

export function computeAutoWeek(startDateStr, todayStr = todayISO()) {
  return computeEffectiveWeek(startDateStr, todayStr, 0);
}

/**
 * B.3 / D7 — calendar with review pauses, ancorado na SEMANA INICIAL:
 * effective_days = (today - start) - total_paused_days
 * week = clamp(start_week + floor(effective_days / 7), start_week, 26)
 *
 * `startWeek` vem do teste de nivelamento (placement). Sem nivelamento vale 1,
 * preservando o comportamento anterior.
 */
export function computeEffectiveWeek(
  startDateStr,
  todayStr = todayISO(),
  totalPausedDays = 0,
  startWeek = 1
) {
  const start = new Date(startDateStr + 'T00:00:00');
  const today = new Date(todayStr + 'T00:00:00');
  const base = Math.min(26, Math.max(1, Number(startWeek) || 1));
  if (Number.isNaN(start.getTime()) || Number.isNaN(today.getTime())) return base;
  const diffDays = Math.floor((today - start) / (1000 * 60 * 60 * 24));
  const paused = Math.max(0, Number(totalPausedDays) || 0);
  const effective = Math.max(0, diffDays - paused);
  const week = base + Math.floor(effective / 7);
  return Math.min(26, Math.max(base, week));
}

function defaultState() {
  return {
    key: 'default',
    start_date: todayISO(),
    current_week: 1,
    week_mode: 'auto',
    reminder_time: null,
    daily_goal_minutes: 30,
    held_back: false,
    review_since: null,
    total_paused_days: 0,
    deficient_topics: null,
    last_pause_increment_date: null,
    quiz_scores: {},
    /** Highest week cleared by checkpoint (0 = none). Mastery hard-gate. */
    mastery_cleared_week: 0,
    /** Semana inicial definida pelo teste de nivelamento (1 = do zero). */
    start_week: 1,
    /** Nivelamento concluído? Enquanto false, a home pede o teste. */
    placement_done: false,
    /** Nível CEFR estimado no nivelamento (A1…C1). */
    placement_level: null,
    /** % de acerto no nivelamento. */
    placement_score: null,
    /** ISO datetime do nivelamento. */
    placement_at: null,
  };
}

/**
 * Mastery hard-gate (audit fix):
 * - held_back: freeze on current practice week
 * - auto: calendar week capped at mastery_cleared_week + 1 (cannot skip without ready)
 * - manual: honor stored current_week (admin override)
 */
/**
 * Program day number: Day 1 = start_date (inclusive).
 * @param {string} startDate YYYY-MM-DD
 * @param {string} [todayStr]
 * @returns {number}
 */
export function programDayNumber(startDate, todayStr = todayISO()) {
  if (!startDate) return 1;
  const start = new Date(String(startDate).slice(0, 10) + 'T00:00:00');
  const today = new Date(String(todayStr).slice(0, 10) + 'T00:00:00');
  if (Number.isNaN(start.getTime()) || Number.isNaN(today.getTime())) return 1;
  const diff = Math.floor((today - start) / (1000 * 60 * 60 * 24));
  return Math.max(1, diff + 1);
}

/** Semana inicial do programa (nivelamento). Default 1. */
export function startWeekOf(state) {
  return Math.min(26, Math.max(1, Number(state?.start_week) || 1));
}

/**
 * Highest week the student may open (quiz-gated).
 * Nunca abaixo da semana inicial definida pelo nivelamento.
 */
export function unlockedWeek(state) {
  if (!state) return 1;
  const base = startWeekOf(state);
  const cleared = Math.max(0, Math.min(26, Number(state.mastery_cleared_week) || 0));
  return Math.min(26, Math.max(base, cleared + 1));
}

/**
 * Current lesson week — NEVER ahead of quiz unlock.
 * Calendar only paces within unlocked window (task v3.1 daily progression).
 */
export function resolveWeek(state) {
  if (!state) return 1;
  const masteryCap = unlockedWeek(state);
  const base = startWeekOf(state);

  if (state.held_back) {
    // Stay on the week under review (never jump while held back)
    const w = Number(state.current_week) || masteryCap;
    return Math.min(26, Math.max(base, Math.min(w, masteryCap)));
  }

  if (state.week_mode === 'manual') {
    // Manual pick still cannot skip locked future weeks nor go below placement
    const want = Math.min(26, Math.max(base, Number(state.current_week) || base));
    return Math.min(want, masteryCap);
  }

  // auto: calendar can lag or lead, but never open a week without quiz pass
  const calendar = calendarWeek(state);
  return Math.max(base, Math.min(calendar, masteryCap));
}

/** Semana que o calendário sozinho indicaria (sem o gate de quiz). */
export function calendarWeek(state) {
  return computeEffectiveWeek(
    state?.start_date,
    todayISO(),
    state?.total_paused_days || 0,
    startWeekOf(state)
  );
}

/**
 * Enrich state for API/UI: program day, locks, quiz gate messaging.
 */
export function enrichProgramProgress(state) {
  const s = state || defaultState();
  const week = resolveWeek(s);
  const unlocked = unlockedWeek(s);
  const day = programDayNumber(s.start_date);
  const quizEntry = s.quiz_scores?.[String(week)] || null;
  const currentWeekQuizPassed = !!(quizEntry && quizEntry.passed);
  const nextWeekLocked = unlocked <= week && week < 26 && !currentWeekQuizPassed;
  const calendar = calendarWeek(s);
  const gateBlocked = calendar > week;
  return {
    ...s,
    current_week: week,
    program_day: day,
    unlocked_week: unlocked,
    start_week: startWeekOf(s),
    calendar_week: calendar,
    /** true quando o calendário já passou da semana liberada pelo quiz */
    gate_blocking_calendar: gateBlocked,
    current_week_quiz_passed: currentWeekQuizPassed,
    next_week_locked: nextWeekLocked,
    placement_done: !!s.placement_done,
    progress_hint: !s.placement_done
      ? 'Faça o teste de nivelamento para descobrir em qual semana você começa.'
      : gateBlocked
        ? `O calendário já está na Semana ${calendar}, mas a Semana ${week} ainda não foi liberada. Faça o Quiz da Semana ${week} (≥70%) — os dias parados não contam contra a sua meta de 6 meses.`
        : nextWeekLocked
          ? `Complete o Quiz da Semana ${week} com nota ≥70% para desbloquear a próxima aula.`
          : week >= 26
            ? 'Você está na última semana do programa.'
            : currentWeekQuizPassed
              ? `Quiz da Semana ${week} aprovado — Semana ${Math.min(26, week + 1)} liberada.`
              : `Dia ${day} do programa · Semana ${week}/26`,
  };
}

/**
 * Acumula 1 dia de pausa por dia de calendário (uma vez por dia) quando o aluno
 * está parado — seja por hold explícito do checkpoint (held_back), seja porque o
 * gate de quiz travou o avanço enquanto o calendário seguia correndo.
 *
 * Sem isso, cada dia sem passar no quiz era perdido para sempre e a data-alvo de
 * 6 meses virava ficção: o calendário disparava na frente da semana real.
 */
function applyDailyPauseIncrement(state) {
  if (!state) return state;
  const blockedByGate = calendarWeek(state) > resolveWeek(state);
  if (!state.held_back && !blockedByGate) return state;
  const today = todayISO();
  if (state.last_pause_increment_date === today) return state;
  return {
    ...state,
    total_paused_days: Math.max(0, Number(state.total_paused_days) || 0) + 1,
    last_pause_increment_date: today,
  };
}

// ─── Seed / Weeks ───────────────────────────────────────────

export async function upsertWeeks(weeksArray) {
  for (const w of weeksArray) {
    const doc = {
      week: w.week,
      phase: w.phase,
      level: w.level,
      title: w.title,
      grammar: w.grammar || '',
      lexis: w.lexis || '',
      persona_city: w.persona_city || 'New York',
      conversation_prompt: w.conversation_prompt || '',
      objectives: w.objectives || [],
      chunks: w.chunks || [],
      anki_sentences: w.anki_sentences || [],
    };
    memoryWeeks.set(doc.week, { ...doc });
    if (mongoEnabled) {
      await ProgramWeek.findOneAndUpdate({ week: doc.week }, doc, {
        upsert: true,
        new: true,
        setDefaultsOnInsert: true,
      });
    }
  }
  return memoryWeeks.size;
}

export async function getAllWeeks() {
  if (mongoEnabled) {
    const rows = await ProgramWeek.find({}).sort({ week: 1 }).lean();
    if (rows.length) return rows;
  }
  return Array.from(memoryWeeks.values()).sort((a, b) => a.week - b.week);
}

export async function getWeek(n) {
  const week = Number(n);
  if (mongoEnabled) {
    const row = await ProgramWeek.findOne({ week }).lean();
    if (row) return row;
  }
  return memoryWeeks.get(week) || null;
}

export function getWeekCount() {
  return memoryWeeks.size;
}

// ─── User state ─────────────────────────────────────────────

function normalizeState(raw) {
  const base = defaultState();
  const s = { ...base, ...(raw || {}) };
  s.held_back = !!s.held_back;
  s.total_paused_days = Math.max(0, Number(s.total_paused_days) || 0);
  s.deficient_topics = s.deficient_topics ?? null;
  s.review_since = s.review_since || null;
  s.last_pause_increment_date = s.last_pause_increment_date || null;
  s.quiz_scores =
    s.quiz_scores && typeof s.quiz_scores === 'object' ? s.quiz_scores : {};
  s.mastery_cleared_week = Math.max(
    0,
    Math.min(26, Number(s.mastery_cleared_week) || 0)
  );
  s.start_week = Math.max(1, Math.min(26, Number(s.start_week) || 1));
  s.placement_done = !!s.placement_done;
  s.placement_level = s.placement_level || null;
  s.placement_score =
    s.placement_score === null || s.placement_score === undefined
      ? null
      : Number(s.placement_score);
  s.placement_at = s.placement_at || null;
  return s;
}

export async function getProgramState() {
  restoreFromFileOnce();
  let state;
  if (mongoEnabled) {
    state = await UserProgramState.findOne({ key: 'default' }).lean();
  }
  if (!state) state = memoryState;
  if (!state) {
    state = defaultState();
    memoryState = { ...state };
    if (mongoEnabled) {
      await UserProgramState.findOneAndUpdate({ key: 'default' }, state, {
        upsert: true,
        new: true,
      });
    }
  }

  let next = normalizeState(state);
  const beforePause = next.total_paused_days;
  next = applyDailyPauseIncrement(next);
  if (next.total_paused_days !== beforePause || next.last_pause_increment_date !== state.last_pause_increment_date) {
    memoryState = { ...next };
    if (mongoEnabled) {
      await UserProgramState.findOneAndUpdate({ key: 'default' }, next, {
        upsert: true,
        new: true,
      });
    }
  } else {
    memoryState = { ...next };
  }
  persistSnapshot();

  return enrichProgramProgress(next);
}

export async function updateProgramState(patch) {
  const current = await getProgramState();
  const next = normalizeState({
    key: 'default',
    start_date: patch.start_date ?? current.start_date,
    current_week: patch.current_week ?? current.current_week,
    week_mode: patch.week_mode ?? current.week_mode,
    reminder_time:
      patch.reminder_time !== undefined ? patch.reminder_time : current.reminder_time,
    daily_goal_minutes: patch.daily_goal_minutes ?? current.daily_goal_minutes,
    held_back: patch.held_back !== undefined ? !!patch.held_back : current.held_back,
    review_since:
      patch.review_since !== undefined ? patch.review_since : current.review_since,
    total_paused_days:
      patch.total_paused_days !== undefined
        ? Math.max(0, Number(patch.total_paused_days) || 0)
        : current.total_paused_days,
    deficient_topics:
      patch.deficient_topics !== undefined
        ? patch.deficient_topics
        : current.deficient_topics,
    last_pause_increment_date:
      patch.last_pause_increment_date !== undefined
        ? patch.last_pause_increment_date
        : current.last_pause_increment_date,
    quiz_scores:
      patch.quiz_scores !== undefined ? patch.quiz_scores : current.quiz_scores,
    mastery_cleared_week:
      patch.mastery_cleared_week !== undefined
        ? Math.max(0, Math.min(26, Number(patch.mastery_cleared_week) || 0))
        : current.mastery_cleared_week,
    start_week:
      patch.start_week !== undefined
        ? Math.max(1, Math.min(26, Number(patch.start_week) || 1))
        : current.start_week,
    placement_done:
      patch.placement_done !== undefined
        ? !!patch.placement_done
        : current.placement_done,
    placement_level:
      patch.placement_level !== undefined
        ? patch.placement_level
        : current.placement_level,
    placement_score:
      patch.placement_score !== undefined
        ? patch.placement_score
        : current.placement_score,
    placement_at:
      patch.placement_at !== undefined ? patch.placement_at : current.placement_at,
  });

  if (next.week_mode !== 'auto' && next.week_mode !== 'manual') {
    const err = new Error('week_mode must be auto or manual');
    err.status = 422;
    throw err;
  }
  next.daily_goal_minutes = Math.max(1, Number(next.daily_goal_minutes) || 30);

  // Always resolve via mastery hard-gate (not pure calendar)
  if (next.week_mode === 'manual' && patch.current_week !== undefined) {
    next.current_week = Math.min(26, Math.max(1, Number(patch.current_week) || 1));
  } else {
    next.current_week = resolveWeek(next);
  }

  memoryState = { ...next };
  if (mongoEnabled) {
    await UserProgramState.findOneAndUpdate({ key: 'default' }, next, {
      upsert: true,
      new: true,
    });
  }
  persistSnapshot();
  return enrichProgramProgress(next);
}

// ─── Nivelamento (placement) ────────────────────────────────

/**
 * Aplica o resultado do nivelamento: define a semana inicial, libera as semanas
 * anteriores (o aluno já as domina) e reancora o calendário na data de hoje.
 *
 * `mastery_cleared_week = start_week - 1` faz o gate de quiz enxergar as semanas
 * puladas como já cumpridas, sem inventar notas falsas em `quiz_scores`.
 *
 * @param {{start_week:number, level:string, score_percent:number}} result
 * @param {{ restartCalendar?: boolean }} [opts] reancorar start_date em hoje (default true)
 */
export async function applyPlacement(result, opts = {}) {
  const restartCalendar = opts.restartCalendar !== false;
  const startWeek = Math.max(1, Math.min(26, Number(result?.start_week) || 1));
  const patch = {
    start_week: startWeek,
    current_week: startWeek,
    mastery_cleared_week: startWeek - 1,
    placement_done: true,
    placement_level: result?.level || null,
    placement_score:
      result?.score_percent === undefined ? null : Number(result.score_percent),
    placement_at: new Date().toISOString(),
    // Nivelou agora → o cronômetro dos 6 meses começa agora, zerado.
    held_back: false,
    review_since: null,
    deficient_topics: null,
    total_paused_days: 0,
    last_pause_increment_date: null,
  };
  if (restartCalendar) patch.start_date = todayISO();
  return updateProgramState(patch);
}

// ─── Quizzes (B.5 / B.6) ────────────────────────────────────

export async function upsertQuizzes(quizSeed) {
  const passing = Number(quizSeed?.passing_score_percent) || 70;
  const weeks = quizSeed?.weeks || [];
  for (const w of weeks) {
    const doc = {
      week: Number(w.week),
      passing_score_percent: passing,
      questions: Array.isArray(w.questions) ? w.questions : [],
    };
    memoryQuizzes.set(doc.week, { ...doc });
    if (mongoEnabled) {
      await ProgramQuiz.findOneAndUpdate({ week: doc.week }, doc, {
        upsert: true,
        new: true,
        setDefaultsOnInsert: true,
      });
    }
  }
  return memoryQuizzes.size;
}

export async function getQuiz(week) {
  const n = Number(week);
  if (mongoEnabled) {
    const row = await ProgramQuiz.findOne({ week: n }).lean();
    if (row) return row;
  }
  return memoryQuizzes.get(n) || null;
}

/**
 * Classify quiz item: vocabulary (grammar/lexis) vs pronunciation.
 * Explicit q.section wins; otherwise heuristic from question text.
 */
export function questionSection(q) {
  const explicit = String(q?.section || '').toLowerCase();
  if (explicit === 'pronunciation' || explicit === 'pronuncia' || explicit === 'pronúncia') {
    return 'pronunciation';
  }
  if (explicit === 'vocabulary' || explicit === 'vocab' || explicit === 'grammar') {
    return 'vocabulary';
  }
  const t = String(q?.question || '').toLowerCase();
  if (
    /som\b|\/[a-zθðæəɪʊɔɑɚɹʃʒŋ]{1,6}\/|ipa|pronúncia|pronuncia|schwa|linking|elisão|elisao|entonação|entonacao|fonema|vogal|consoante|mouth|tongue|stress|sílaba|silaba/.test(
      t
    )
  ) {
    return 'pronunciation';
  }
  return 'vocabulary';
}

/** Public payload: strip correct answers until submit. */
export function quizForClient(quizDoc) {
  if (!quizDoc) return null;
  const questions = (quizDoc.questions || []).map((q) => ({
    question: q.question,
    options: q.options || [],
    section: questionSection(q),
  }));
  return {
    week: quizDoc.week,
    passing_score_percent: quizDoc.passing_score_percent ?? 70,
    questions,
    sections: {
      vocabulary: questions.filter((q) => q.section === 'vocabulary').length,
      pronunciation: questions.filter((q) => q.section === 'pronunciation').length,
    },
  };
}

export async function submitQuizAnswers(week, answers) {
  const quiz = await getQuiz(week);
  if (!quiz) {
    const err = new Error(`Quiz for week ${week} not found`);
    err.status = 404;
    throw err;
  }
  const questions = quiz.questions || [];
  const ans = Array.isArray(answers) ? answers : [];
  let correct = 0;
  let vocabCorrect = 0;
  let vocabTotal = 0;
  let pronCorrect = 0;
  let pronTotal = 0;
  const correct_answers = [];
  for (let i = 0; i < questions.length; i++) {
    const q = questions[i];
    const section = questionSection(q);
    const ci = Number(q.correct_index);
    const chosen = ans[i];
    const ok = chosen !== undefined && Number(chosen) === ci;
    if (ok) correct += 1;
    if (section === 'pronunciation') {
      pronTotal += 1;
      if (ok) pronCorrect += 1;
    } else {
      vocabTotal += 1;
      if (ok) vocabCorrect += 1;
    }
    correct_answers.push({
      index: i,
      correct_index: ci,
      correct_answer: q.correct_answer ?? q.options?.[ci] ?? '',
      chosen_index: chosen === undefined ? null : Number(chosen),
      is_correct: ok,
      section,
    });
  }
  const total = questions.length || 1;
  const score_percent = Math.round((correct / total) * 100);
  const vocabulary_score =
    vocabTotal > 0 ? Math.round((vocabCorrect / vocabTotal) * 100) : score_percent;
  const pronunciation_score =
    pronTotal > 0 ? Math.round((pronCorrect / pronTotal) * 100) : score_percent;
  // Combined score: average of the two sections when both exist (task v3.1)
  const sectionScores = [];
  if (vocabTotal > 0) sectionScores.push(vocabulary_score);
  if (pronTotal > 0) sectionScores.push(pronunciation_score);
  const combined_score =
    sectionScores.length > 0
      ? Math.round(sectionScores.reduce((a, b) => a + b, 0) / sectionScores.length)
      : score_percent;
  const passMark = Number(quiz.passing_score_percent) || 70;
  const passed = combined_score >= passMark;
  const wrong_hints = correct_answers
    .filter((c) => !c.is_correct)
    .map((c) => questions[c.index]?.question || `Questão ${c.index + 1}`)
    .slice(0, 10);

  // Persist latest score for this week on user state
  const state = await getProgramState();
  const scores = { ...(state.quiz_scores || {}) };
  scores[String(week)] = {
    score_percent: combined_score,
    vocabulary_score,
    pronunciation_score,
    passed,
    submitted_at: new Date().toISOString(),
    wrong_hints,
  };

  /**
   * Daily / lesson progression (task v3.1):
   * only unlock next week after passing the quiz for the current week.
   * mastery_cleared_week = W means weeks 1..W are cleared → unlocked = W+1.
   */
  const patch = { quiz_scores: scores };
  let unlockedAfter = unlockedWeek(state);
  let advanced = false;
  if (passed) {
    const prevCleared = Math.max(0, Number(state.mastery_cleared_week) || 0);
    const newCleared = Math.max(prevCleared, Number(week) || 0);
    if (newCleared > prevCleared) {
      patch.mastery_cleared_week = newCleared;
      advanced = true;
    } else {
      patch.mastery_cleared_week = newCleared;
    }
    // Passing the week under review clears soft hold
    if (state.held_back && Number(state.current_week) === Number(week)) {
      patch.held_back = false;
      patch.review_since = null;
      patch.deficient_topics = null;
    }
  }

  const updated = await updateProgramState(patch);
  unlockedAfter = unlockedWeek(updated);

  return {
    score_percent: combined_score,
    vocabulary_score,
    pronunciation_score,
    passed,
    can_advance: passed,
    advanced,
    unlocked_week: unlockedAfter,
    program_day: programDayNumber(updated.start_date),
    progress_hint: passed
      ? `Aula desbloqueada: Semana ${unlockedAfter}/26. Continue no Dia ${programDayNumber(updated.start_date)}.`
      : `Complete o Quiz com nota ≥${passMark}% para avançar. (Dia ${programDayNumber(updated.start_date)})`,
    passing_score_percent: passMark,
    correct_count: correct,
    total,
    vocabulary_correct: vocabCorrect,
    vocabulary_total: vocabTotal,
    pronunciation_correct: pronCorrect,
    pronunciation_total: pronTotal,
    correct_answers,
  };
}

export async function getSessionsForWeek(week) {
  const n = Number(week);
  const all = await listSessions();
  return all.filter((s) => Number(s.week) === n);
}

/**
 * Run weekly checkpoint (B.4). Mutates held_back / deficient_topics.
 */
export async function runCheckpoint() {
  const {
    evaluateReadiness,
    buildDeficientTopics,
  } = await import('./evaluateReadiness.js');

  const state = await getProgramState();
  const week = state.current_week;
  const weekDoc = await getWeek(week);
  const quizDoc = await getQuiz(week);
  const passMark = quizDoc?.passing_score_percent ?? 70;
  const quizEntry = state.quiz_scores?.[String(week)] || null;
  const quizScore = quizEntry?.score_percent ?? null;

  const sessions = await getSessionsForWeek(week);
  const feedbackList = sessions
    .map((s) => s.feedback_json)
    .filter((f) => f && typeof f === 'object');
  const cefrEstimates = feedbackList
    .map((f) => f.cefr_estimate)
    .filter(Boolean);

  const result = evaluateReadiness({
    quizScorePercent: quizScore,
    passingScorePercent: passMark,
    cefrEstimates,
    expectedLevel: weekDoc?.level || '',
    feedbackList,
  });

  if (result.ready) {
    const cleared = Math.max(
      Number(state.mastery_cleared_week) || 0,
      Number(week) || 0
    );
    await updateProgramState({
      held_back: false,
      review_since: null,
      deficient_topics: null,
      mastery_cleared_week: cleared,
      // keep total_paused_days so calendar stays adjusted
    });
    const fresh = await getProgramState();
    return {
      ready: true,
      reasons: [],
      deficient_topics: null,
      details: result.details,
      state: fresh,
      mastery_cleared_week: cleared,
    };
  }

  const wrongHints = Array.isArray(quizEntry?.wrong_hints)
    ? quizEntry.wrong_hints
    : [];
  const deficient = buildDeficientTopics({
    weekTitle: weekDoc?.title || `Semana ${week}`,
    wrongQuestionHints: wrongHints,
    feedbackList,
  });

  await updateProgramState({
    held_back: true,
    review_since: todayISO(),
    deficient_topics: deficient,
    // freeze practice week at the failed checkpoint week
    current_week: week,
    week_mode: state.week_mode === 'manual' ? 'manual' : 'auto',
  });
  const fresh = await getProgramState();
  return {
    ready: false,
    reasons: result.reasons,
    deficient_topics: deficient,
    details: result.details,
    state: fresh,
  };
}

// ─── Practice sessions ──────────────────────────────────────

export async function createSession({ week, type, started_at }) {
  const id = randomUUID();
  const doc = {
    id,
    week: Number(week),
    type,
    started_at: started_at ? new Date(started_at) : new Date(),
    ended_at: null,
    duration_seconds: 0,
    feedback_json: null,
    feedback_status: 'none',
  };
  if (!['themed', 'quick', 'chunks'].includes(type)) {
    const err = new Error('type must be themed, quick, or chunks');
    err.status = 422;
    throw err;
  }
  memorySessions.set(id, { ...doc });
  if (mongoEnabled) {
    await PracticeSession.create(doc);
  }
  persistSnapshot();
  return { id };
}

export async function endSession(id, { ended_at, duration_seconds, feedback_json, feedback_status }) {
  const existing = await getSession(id);
  if (!existing) return null;

  const patch = {};
  if (ended_at !== undefined) {
    patch.ended_at = ended_at ? new Date(ended_at) : new Date();
  }
  if (duration_seconds !== undefined) {
    patch.duration_seconds = Math.max(0, Number(duration_seconds) || 0);
  }
  if (feedback_json !== undefined) patch.feedback_json = feedback_json;
  if (feedback_status !== undefined) patch.feedback_status = feedback_status;

  const updated = { ...existing, ...patch };
  memorySessions.set(id, updated);
  if (mongoEnabled) {
    await PracticeSession.findOneAndUpdate({ id }, patch, { new: true });
  }
  persistSnapshot();
  return updated;
}

export async function getSession(id) {
  restoreFromFileOnce();
  if (mongoEnabled) {
    const row = await PracticeSession.findOne({ id }).lean();
    if (row) return row;
  }
  return memorySessions.get(id) || null;
}

export async function updateSessionFeedback(id, feedback_json, feedback_status) {
  const s = await getSession(id);
  if (!s) return null;
  const patch = { feedback_json, feedback_status };
  const updated = { ...s, ...patch };
  memorySessions.set(id, updated);
  if (mongoEnabled) {
    await PracticeSession.findOneAndUpdate({ id }, patch);
  }
  persistSnapshot();
  return updated;
}

export async function listSessions() {
  restoreFromFileOnce();
  if (mongoEnabled) {
    const rows = await PracticeSession.find({}).lean();
    if (rows.length) return rows;
  }
  return Array.from(memorySessions.values());
}

/**
 * Progress summary for last N days.
 */
export async function getProgressSummary(days = 30) {
  const state = await getProgramState();
  const sessions = await listSessions();
  const completed = sessions.filter((s) => s.ended_at && s.duration_seconds > 0);

  const dayMap = new Map();
  const today = todayISO();
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - i);
    const key = d.toISOString().slice(0, 10);
    dayMap.set(key, 0);
  }

  for (const s of completed) {
    const dateKey = new Date(s.ended_at || s.started_at).toISOString().slice(0, 10);
    if (dayMap.has(dateKey)) {
      dayMap.set(dateKey, dayMap.get(dateKey) + (s.duration_seconds || 0));
    } else if (dateKey === today) {
      dayMap.set(dateKey, (dayMap.get(dateKey) || 0) + (s.duration_seconds || 0));
    }
  }

  const daysArr = Array.from(dayMap.entries()).map(([date, seconds]) => ({
    date,
    minutes: Math.round(seconds / 60),
  }));

  const todaySeconds = completed
    .filter((s) => new Date(s.ended_at || s.started_at).toISOString().slice(0, 10) === today)
    .reduce((a, s) => a + (s.duration_seconds || 0), 0);

  // Streak: consecutive days ending today (or yesterday if today empty) with >=1 session
  const practiceDates = new Set(
    completed.map((s) => new Date(s.ended_at || s.started_at).toISOString().slice(0, 10))
  );

  function streakFrom(endDateStr) {
    let streak = 0;
    const d = new Date(endDateStr + 'T00:00:00');
    while (true) {
      const key = d.toISOString().slice(0, 10);
      if (practiceDates.has(key)) {
        streak++;
        d.setDate(d.getDate() - 1);
      } else break;
    }
    return streak;
  }

  let streak = streakFrom(today);
  if (streak === 0) {
    const y = new Date();
    y.setDate(y.getDate() - 1);
    streak = streakFrom(y.toISOString().slice(0, 10));
  }

  // best streak (simple scan)
  const sortedDates = Array.from(practiceDates).sort();
  let best = 0;
  let run = 0;
  let prev = null;
  for (const ds of sortedDates) {
    if (!prev) {
      run = 1;
    } else {
      const prevD = new Date(prev + 'T00:00:00');
      const curD = new Date(ds + 'T00:00:00');
      const diff = (curD - prevD) / (1000 * 60 * 60 * 24);
      run = diff === 1 ? run + 1 : 1;
    }
    best = Math.max(best, run);
    prev = ds;
  }

  const week = state.current_week;
  const phase = week <= 6 ? 1 : week <= 13 ? 2 : week <= 20 ? 3 : 4;

  return {
    today_minutes: Math.round(todaySeconds / 60),
    goal: state.daily_goal_minutes || 30,
    streak,
    best_streak: Math.max(best, streak),
    days: daysArr,
    current_week: week,
    phase,
  };
}
