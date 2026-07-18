/**
 * Unit tests for evaluateReadiness + week pause formula (Fase 2 / D6–D7).
 */
import assert from 'assert';
import {
  evaluateReadiness,
  cefrAtLeast,
  bestCefrEstimate,
  conservativeCefrEstimate,
  countCriticalMistakes,
  buildDeficientTopics,
  normalizeCefr,
} from './services/evaluateReadiness.js';
import {
  computeAutoWeek,
  computeEffectiveWeek,
  resolveWeek,
  programDayNumber,
  unlockedWeek,
  enrichProgramProgress,
} from './services/programStore.js';

// CEFR ordinal
assert.strictEqual(cefrAtLeast('B1', 'A2'), true);
assert.strictEqual(cefrAtLeast('A2', 'B1'), false);
assert.strictEqual(cefrAtLeast('B1', 'B1'), true);
assert.strictEqual(normalizeCefr(' b1 '), 'B1');
assert.strictEqual(bestCefrEstimate(['A2', 'B1', 'A1']), 'B1');
assert.strictEqual(bestCefrEstimate([]), null);
assert.strictEqual(conservativeCefrEstimate(['A2', 'B1', 'A1']), 'A1');
assert.strictEqual(conservativeCefrEstimate([]), null);

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

// Fail CEFR (conservative: lowest estimate A2 blocks B1 week even if another session was B1)
{
  const r = evaluateReadiness({
    quizScorePercent: 90,
    cefrEstimates: ['B1', 'A2'],
    expectedLevel: 'B1',
    feedbackList: [{ mistakes: [] }],
  });
  assert.strictEqual(r.ready, false);
  assert.ok(r.reasons.some((x) => /CEFR/i.test(x)));
  assert.strictEqual(r.details.gate_cefr, 'A2');
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

// Mastery hard-gate: calendar week 3 but nothing cleared → stay week 1
assert.strictEqual(
  resolveWeek({
    week_mode: 'auto',
    start_date: '2026-07-01',
    total_paused_days: 0,
    mastery_cleared_week: 0,
    held_back: false,
    current_week: 1,
  }),
  1
);
// Cleared week 1 → can open week 2 even if calendar says 3
assert.strictEqual(
  resolveWeek({
    week_mode: 'auto',
    start_date: '2026-07-01',
    total_paused_days: 0,
    mastery_cleared_week: 1,
    held_back: false,
  }),
  2
);
// Cleared 2 + calendar 3 → week 3
assert.strictEqual(
  resolveWeek({
    week_mode: 'auto',
    start_date: '2026-07-01',
    total_paused_days: 0,
    mastery_cleared_week: 2,
    held_back: false,
  }),
  3
);
// held_back freezes at current (capped)
assert.strictEqual(
  resolveWeek({
    week_mode: 'auto',
    start_date: '2026-07-01',
    total_paused_days: 0,
    mastery_cleared_week: 1,
    held_back: true,
    current_week: 2,
  }),
  2
);

// Day 1 of program = start_date
assert.strictEqual(programDayNumber('2026-07-18', '2026-07-18'), 1);
assert.strictEqual(programDayNumber('2026-07-01', '2026-07-15'), 15);
assert.strictEqual(unlockedWeek({ mastery_cleared_week: 0 }), 1);
assert.strictEqual(unlockedWeek({ mastery_cleared_week: 3 }), 4);

// Without quiz pass, cannot open week 2 even if calendar is far ahead
assert.strictEqual(
  resolveWeek({
    week_mode: 'auto',
    start_date: '2026-01-01',
    total_paused_days: 0,
    mastery_cleared_week: 0,
    held_back: false,
  }),
  1
);

const enriched = enrichProgramProgress({
  start_date: '2026-07-18',
  mastery_cleared_week: 0,
  week_mode: 'auto',
  quiz_scores: {},
  total_paused_days: 0,
  held_back: false,
  current_week: 1,
});
assert.strictEqual(enriched.program_day >= 1, true);
assert.strictEqual(enriched.unlocked_week, 1);
assert.strictEqual(enriched.next_week_locked, true);
assert.ok(String(enriched.progress_hint).includes('Quiz'));

console.log('✅ evaluateReadiness + pause calendar + mastery gate tests passed');
