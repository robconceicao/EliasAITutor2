/**
 * REST API for Modo Programa (§4.5)
 */
import express from 'express';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import {
  getAllWeeks,
  getWeek,
  getProgramState,
  updateProgramState,
  createSession,
  endSession,
  getSession,
  getProgressSummary,
  getQuiz,
  quizForClient,
  submitQuizAnswers,
  runCheckpoint,
} from '../services/programStore.js';
import {
  generateSessionFeedback,
  getSessionFeedback,
} from '../services/sessionFeedback.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const router = express.Router();

function sendError(res, status, error, message) {
  return res.status(status).json({ error, message });
}

// GET /program/weeks
router.get('/program/weeks', async (_req, res) => {
  try {
    const weeks = await getAllWeeks();
    res.json(weeks);
  } catch (e) {
    sendError(res, 500, 'internal', e.message);
  }
});

// GET /program/weeks/:n
router.get('/program/weeks/:n', async (req, res) => {
  try {
    const n = Number(req.params.n);
    if (!Number.isInteger(n) || n < 1 || n > 26) {
      return sendError(res, 404, 'not_found', `Week ${req.params.n} not found`);
    }
    const week = await getWeek(n);
    if (!week) return sendError(res, 404, 'not_found', `Week ${n} not found`);
    res.json(week);
  } catch (e) {
    sendError(res, 500, 'internal', e.message);
  }
});

// GET /program/state
router.get('/program/state', async (_req, res) => {
  try {
    const state = await getProgramState();
    res.json(state);
  } catch (e) {
    sendError(res, 500, 'internal', e.message);
  }
});

// PUT /program/state
router.put('/program/state', async (req, res) => {
  try {
    const body = req.body || {};
    const allowed = [
      'start_date',
      'current_week',
      'week_mode',
      'reminder_time',
      'daily_goal_minutes',
      // adaptive fields usually set via checkpoint, but allow admin/debug patches
      'held_back',
      'review_since',
      'total_paused_days',
      'deficient_topics',
    ];
    const patch = {};
    for (const k of allowed) {
      if (body[k] !== undefined) patch[k] = body[k];
    }
    if (Object.keys(patch).length === 0) {
      return sendError(res, 422, 'invalid', 'No valid fields to update');
    }
    const state = await updateProgramState(patch);
    res.json(state);
  } catch (e) {
    sendError(res, e.status || 500, e.status === 422 ? 'invalid' : 'internal', e.message);
  }
});

// GET /program/quiz/:week — no correct_index / correct_answer (B.6)
router.get('/program/quiz/:week', async (req, res) => {
  try {
    const n = Number(req.params.week);
    if (!Number.isInteger(n) || n < 1 || n > 26) {
      return sendError(res, 404, 'not_found', `Quiz week ${req.params.week} not found`);
    }
    const quiz = await getQuiz(n);
    if (!quiz) return sendError(res, 404, 'not_found', `Quiz for week ${n} not found`);
    res.json(quizForClient(quiz));
  } catch (e) {
    sendError(res, 500, 'internal', e.message);
  }
});

// POST /program/quiz/:week/submit — { answers: [index, ...] }
router.post('/program/quiz/:week/submit', async (req, res) => {
  try {
    const n = Number(req.params.week);
    if (!Number.isInteger(n) || n < 1 || n > 26) {
      return sendError(res, 404, 'not_found', `Quiz week ${req.params.week} not found`);
    }
    const answers = req.body?.answers;
    if (!Array.isArray(answers)) {
      return sendError(res, 422, 'invalid', 'answers must be an array of option indices');
    }
    const result = await submitQuizAnswers(n, answers);
    res.json(result);
  } catch (e) {
    sendError(res, e.status || 500, e.status === 404 ? 'not_found' : 'internal', e.message);
  }
});

// POST /program/checkpoint — weekly readiness gate (B.4)
router.post('/program/checkpoint', async (_req, res) => {
  try {
    const result = await runCheckpoint();
    res.json(result);
  } catch (e) {
    sendError(res, 500, 'internal', e.message);
  }
});

// POST /sessions
router.post('/sessions', async (req, res) => {
  try {
    const { week, type, started_at } = req.body || {};
    if (week == null || !type) {
      return sendError(res, 422, 'invalid', 'week and type are required');
    }
    const result = await createSession({ week, type, started_at });
    res.status(201).json(result);
  } catch (e) {
    sendError(res, e.status || 500, e.status === 422 ? 'invalid' : 'internal', e.message);
  }
});

// PATCH /sessions/:id/end
router.patch('/sessions/:id/end', async (req, res) => {
  try {
    const { id } = req.params;
    const { ended_at, duration_seconds, transcript } = req.body || {};
    const session = await getSession(id);
    if (!session) return sendError(res, 404, 'not_found', 'Session not found');

    const duration = Math.max(0, Number(duration_seconds) || 0);
    const updated = await endSession(id, {
      ended_at: ended_at || new Date().toISOString(),
      duration_seconds: duration,
    });

    // F8: full report only for themed/quick >= 10 min (Elias master prompt)
    const needsFeedback =
      (session.type === 'themed' || session.type === 'quick') && duration >= 600;

    let feedback_status = 'none';
    if (needsFeedback) {
      feedback_status = 'pending';
      // fire-and-forget; transcript from client if provided
      const history = typeof transcript === 'string' ? transcript : transcript || '';
      const weekDoc = await getWeek(session.week).catch(() => null);
      const weekMeta = {
        week: session.week,
        title: weekDoc?.title,
        level: weekDoc?.level,
      };
      generateSessionFeedback(id, history, weekMeta).catch((err) =>
        console.error('[feedback]', err.message)
      );
    }

    res.json({ id, feedback_status: needsFeedback ? 'pending' : 'none' });
  } catch (e) {
    sendError(res, 500, 'internal', e.message);
  }
});

// GET /sessions/:id/feedback
router.get('/sessions/:id/feedback', async (req, res) => {
  try {
    const fb = await getSessionFeedback(req.params.id);
    if (!fb) return sendError(res, 404, 'not_found', 'Session not found');
    if (fb.feedback_status === 'pending') {
      return res.status(202).json({ feedback_status: 'pending' });
    }
    if (fb.feedback_status === 'failed' || !fb.feedback_json) {
      return res.json({ feedback_status: fb.feedback_status || 'failed', feedback_json: null });
    }
    res.json(fb.feedback_json);
  } catch (e) {
    sendError(res, 500, 'internal', e.message);
  }
});

// POST /sessions/:id/feedback/retry
router.post('/sessions/:id/feedback/retry', async (req, res) => {
  try {
    const session = await getSession(req.params.id);
    if (!session) return sendError(res, 404, 'not_found', 'Session not found');
    const transcript = req.body?.transcript || '';
    const weekDoc = await getWeek(session.week).catch(() => null);
    const result = await generateSessionFeedback(req.params.id, transcript, {
      week: session.week,
      title: weekDoc?.title,
      level: weekDoc?.level,
    });
    res.json(result);
  } catch (e) {
    sendError(res, 500, 'internal', e.message);
  }
});

// GET /progress/summary
router.get('/progress/summary', async (req, res) => {
  try {
    const days = Math.min(90, Math.max(1, Number(req.query.days) || 30));
    const summary = await getProgressSummary(days);
    res.json(summary);
  } catch (e) {
    sendError(res, 500, 'internal', e.message);
  }
});

// Static chunk audio: GET /program/chunks/audio/:week/:index
router.get('/program/chunks/audio/:week/:index', (req, res) => {
  const week = Number(req.params.week);
  const index = Number(req.params.index);
  const file = path.resolve(
    __dirname,
    `../cache/chunks/w${String(week).padStart(2, '0')}_${String(index).padStart(2, '0')}.mp3`
  );
  if (!fs.existsSync(file)) {
    return sendError(res, 404, 'not_found', 'Chunk audio not found');
  }
  res.setHeader('Content-Type', 'audio/mpeg');
  res.sendFile(file);
});

export default router;
