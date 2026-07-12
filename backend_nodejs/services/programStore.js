/**
 * Program data store: MongoDB when available, in-memory fallback otherwise.
 */
import { randomUUID } from 'crypto';
import {
  ProgramWeek,
  UserProgramState,
  PracticeSession,
} from '../models/programModels.js';

/** @type {Map<number, object>} */
const memoryWeeks = new Map();
/** @type {object|null} */
let memoryState = null;
/** @type {Map<string, object>} */
const memorySessions = new Map();

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
  const start = new Date(startDateStr + 'T00:00:00');
  const today = new Date(todayStr + 'T00:00:00');
  if (Number.isNaN(start.getTime()) || Number.isNaN(today.getTime())) return 1;
  const diffDays = Math.floor((today - start) / (1000 * 60 * 60 * 24));
  const week = 1 + Math.floor(diffDays / 7);
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
  };
}

function resolveWeek(state) {
  if (!state) return 1;
  if (state.week_mode === 'auto') {
    return computeAutoWeek(state.start_date);
  }
  return Math.min(26, Math.max(1, state.current_week || 1));
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
  const current_week = resolveWeek(state);
  return { ...state, current_week };
}

export async function updateProgramState(patch) {
  const current = await getProgramState();
  const next = {
    key: 'default',
    start_date: patch.start_date ?? current.start_date,
    current_week: patch.current_week ?? current.current_week,
    week_mode: patch.week_mode ?? current.week_mode,
    reminder_time:
      patch.reminder_time !== undefined ? patch.reminder_time : current.reminder_time,
    daily_goal_minutes: patch.daily_goal_minutes ?? current.daily_goal_minutes,
  };

  if (next.week_mode !== 'auto' && next.week_mode !== 'manual') {
    const err = new Error('week_mode must be auto or manual');
    err.status = 422;
    throw err;
  }
  next.current_week = Math.min(26, Math.max(1, Number(next.current_week) || 1));
  next.daily_goal_minutes = Math.max(1, Number(next.daily_goal_minutes) || 30);

  if (next.week_mode === 'auto') {
    next.current_week = computeAutoWeek(next.start_date);
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
