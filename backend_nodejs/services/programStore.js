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

/** @type {Map<number, object>} */
const memoryWeeks = new Map();
/** @type {object|null} */
let memoryState = null;
/** @type {Map<string, object>} */
const memorySessions = new Map();
/** @type {Map<number, object>} week → quiz doc */
const memoryQuizzes = new Map();

let mongoEnabled = false;

export function setMongoEnabled(flag) {
  mongoEnabled = !!flag;
}

export function isMongoEnabled() {
  return mongoEnabled;
}

function todayISO() {
  return new Date().toISOString().slice(0, 10);
}

export function computeAutoWeek(startDateStr, todayStr = todayISO()) {
  return computeEffectiveWeek(startDateStr, todayStr, 0);
}

/**
 * B.3 / D7 — calendar with review pauses:
 * effective_days = (today - start) - total_paused_days
 * week = clamp(1 + floor(effective_days / 7), 1, 26)
 */
export function computeEffectiveWeek(
  startDateStr,
  todayStr = todayISO(),
  totalPausedDays = 0
) {
  const start = new Date(startDateStr + 'T00:00:00');
  const today = new Date(todayStr + 'T00:00:00');
  if (Number.isNaN(start.getTime()) || Number.isNaN(today.getTime())) return 1;
  const diffDays = Math.floor((today - start) / (1000 * 60 * 60 * 24));
  const paused = Math.max(0, Number(totalPausedDays) || 0);
  const effective = Math.max(0, diffDays - paused);
  const week = 1 + Math.floor(effective / 7);
  return Math.min(26, Math.max(1, week));
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
  };
}

function resolveWeek(state) {
  if (!state) return 1;
  if (state.week_mode === 'auto') {
    return computeEffectiveWeek(
      state.start_date,
      todayISO(),
      state.total_paused_days || 0
    );
  }
  return Math.min(26, Math.max(1, state.current_week || 1));
}

/** While held_back, add 1 paused day per calendar day (once). */
function applyDailyPauseIncrement(state) {
  if (!state?.held_back) return state;
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
  return s;
}

export async function getProgramState() {
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

  const current_week = resolveWeek(next);
  return {
    ...next,
    current_week,
  };
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
  });

  if (next.week_mode !== 'auto' && next.week_mode !== 'manual') {
    const err = new Error('week_mode must be auto or manual');
    err.status = 422;
    throw err;
  }
  next.current_week = Math.min(26, Math.max(1, Number(next.current_week) || 1));
  next.daily_goal_minutes = Math.max(1, Number(next.daily_goal_minutes) || 30);

  if (next.week_mode === 'auto') {
    next.current_week = computeEffectiveWeek(
      next.start_date,
      todayISO(),
      next.total_paused_days || 0
    );
  }

  memoryState = { ...next };
  if (mongoEnabled) {
    await UserProgramState.findOneAndUpdate({ key: 'default' }, next, {
      upsert: true,
      new: true,
    });
  }
  return { ...next, current_week: resolveWeek(next) };
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

/** Public payload: strip correct answers until submit. */
export function quizForClient(quizDoc) {
  if (!quizDoc) return null;
  return {
    week: quizDoc.week,
    passing_score_percent: quizDoc.passing_score_percent ?? 70,
    questions: (quizDoc.questions || []).map((q) => ({
      question: q.question,
      options: q.options || [],
    })),
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
  const correct_answers = [];
  for (let i = 0; i < questions.length; i++) {
    const q = questions[i];
    const ci = Number(q.correct_index);
    const chosen = ans[i];
    const ok = chosen !== undefined && Number(chosen) === ci;
    if (ok) correct += 1;
    correct_answers.push({
      index: i,
      correct_index: ci,
      correct_answer: q.correct_answer ?? q.options?.[ci] ?? '',
      chosen_index: chosen === undefined ? null : Number(chosen),
      is_correct: ok,
    });
  }
  const total = questions.length || 1;
  const score_percent = Math.round((correct / total) * 100);
  const passMark = Number(quiz.passing_score_percent) || 70;
  const passed = score_percent >= passMark;
  const wrong_hints = correct_answers
    .filter((c) => !c.is_correct)
    .map((c) => questions[c.index]?.question || `Questão ${c.index + 1}`)
    .slice(0, 10);

  // Persist latest score for this week on user state
  const state = await getProgramState();
  const scores = { ...(state.quiz_scores || {}) };
  scores[String(week)] = {
    score_percent,
    passed,
    submitted_at: new Date().toISOString(),
    wrong_hints,
  };
  await updateProgramState({ quiz_scores: scores });

  return {
    score_percent,
    passed,
    passing_score_percent: passMark,
    correct_count: correct,
    total,
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
    await updateProgramState({
      held_back: false,
      review_since: null,
      deficient_topics: null,
      // keep total_paused_days so calendar stays adjusted
    });
    const fresh = await getProgramState();
    return {
      ready: true,
      reasons: [],
      deficient_topics: null,
      details: result.details,
      state: fresh,
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
  return updated;
}

export async function getSession(id) {
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
  return updated;
}

export async function listSessions() {
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
