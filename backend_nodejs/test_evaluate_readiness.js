/**
 * Unit tests for evaluateReadiness + week pause formula (Fase 2 / D6–D7).
 */
import assert from 'assert';
import {
  evaluateReadiness,
  cefrAtLeast,
  bestCefrEstimate,
  countCriticalMistakes,
  buildDeficientTopics,
  normalizeCefr,
} from './services/evaluateReadiness.js';
import { computeAutoWeek, computeEffectiveWeek } from './services/programStore.js';

// CEFR ordinal
assert.strictEqual(cefrAtLeast('B1', 'A2'), true);
assert.strictEqual(cefrAtLeast('A2', 'B1'), false);
assert.strictEqual(cefrAtLeast('B1', 'B1'), true);
assert.strictEqual(normalizeCefr(' b1 '), 'B1');
assert.strictEqual(bestCefrEstimate(['A2', 'B1', 'A1']), 'B1');
assert.strictEqual(bestCefrEstimate([]), null);

assert.strictEqual(
  countCriticalMistakes([
    { mistakes: [{ severity: 'critical' }, { severity: 'minor' }] },
    { mistakes: [{ severity: 'critical' }] },
  ]),
  2
);
// missing severity = minor
assert.strictEqual(
  countCriticalMistakes([{ mistakes: [{ said: 'x' }] }]),
  0
);

// Ready: all three
{
  const r = evaluateReadiness({
    quizScorePercent: 80,
    passingScorePercent: 70,
    cefrEstimates: ['B1'],
    expectedLevel: 'B1',
    feedbackList: [
      { mistakes: [{ severity: 'minor' }, { severity: 'critical' }] },
    ],
  });
  assert.strictEqual(r.ready, true, 'all three pass');
  assert.strictEqual(r.reasons.length, 0);
}

// Fail quiz only
{
  const r = evaluateReadiness({
    quizScorePercent: 50,
    passingScorePercent: 70,
    cefrEstimates: ['B1'],
    expectedLevel: 'B1',
    feedbackList: [{ mistakes: [] }],
  });
  assert.strictEqual(r.ready, false);
  assert.ok(r.reasons.some((x) => /Quiz/i.test(x)));
}

// Fail CEFR
{
  const r = evaluateReadiness({
    quizScorePercent: 90,
    cefrEstimates: ['A2'],
    expectedLevel: 'B1',
    feedbackList: [{ mistakes: [] }],
  });
  assert.strictEqual(r.ready, false);
  assert.ok(r.reasons.some((x) => /CEFR/i.test(x)));
}

// Fail critical count
{
  const r = evaluateReadiness({
    quizScorePercent: 90,
    cefrEstimates: ['B1'],
    expectedLevel: 'B1',
    feedbackList: [
      {
        mistakes: [
          { severity: 'critical' },
          { severity: 'critical' },
          { severity: 'critical' },
        ],
      },
    ],
  });
  assert.strictEqual(r.ready, false);
  assert.ok(r.reasons.some((x) => /críticos/i.test(x)));
}

// Missing quiz
{
  const r = evaluateReadiness({
    quizScorePercent: null,
    cefrEstimates: ['B1'],
    expectedLevel: 'B1',
    feedbackList: [],
  });
  assert.strictEqual(r.ready, false);
}

// Deficient topics
{
  const topics = buildDeficientTopics({
    weekTitle: 'Comparatives',
    wrongQuestionHints: ['taller vs more tall'],
    feedbackList: [
      {
        mistakes: [{ severity: 'critical', note: 'Past simple' }],
        next_focus: 'Drill will/going to',
      },
    ],
  });
  assert.ok(topics.includes('taller vs more tall'));
  assert.ok(topics.some((t) => /Past simple/i.test(t)));
  assert.ok(topics.some((t) => /Próximo foco/i.test(t)));
}

// Calendar with pause (D7)
// start 2026-07-01, today 2026-07-15 = 14 days → week 3 without pause
assert.strictEqual(computeAutoWeek('2026-07-01', '2026-07-15'), 3);
assert.strictEqual(
  computeEffectiveWeek('2026-07-01', '2026-07-15', 0),
  3
);
// 7 paused days → effective 7 days → week 2
assert.strictEqual(
  computeEffectiveWeek('2026-07-01', '2026-07-15', 7),
  2
);
// hold calendar: 21 calendar days, 14 paused → effective 7 → week 2
assert.strictEqual(
  computeEffectiveWeek('2026-07-01', '2026-07-22', 14),
  2
);

console.log('✅ evaluateReadiness + pause calendar tests passed');
