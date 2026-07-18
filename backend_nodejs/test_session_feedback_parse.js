/**
 * Unit tests for C1-aligned feedback JSON parser.
 */
import assert from 'assert';
import { parseFeedbackJson } from './services/sessionFeedback.js';

const sample = parseFeedbackJson(`{
  "strengths":["usou because","ritmo melhor"],
  "mistakes":[{"said":"I go","correct":"I went","note":"passado","ipa":"/wɛnt/","mouth_tip":"","severity":"critical"}],
  "better_phrases":["I ended up going"],
  "pronunciation_focus":"schwa em about",
  "discourse_focus":"falta concessão (however)",
  "cefr_estimate":"B1",
  "week_alignment":"Semana 9 — planos futuros parcialmente cobertos",
  "recovery_plan":{"priority":"discourse","daily_drills":["1 min monólogo com however"],"success_criteria":"usa 2 marcadores"},
  "next_focus":"opinião + razão + exemplo",
  "motivation":"Continue forçando turnos longos."
}`);

assert.ok(sample);
assert.strictEqual(sample.strengths.length, 2);
assert.strictEqual(sample.mistakes[0].severity, 'critical');
assert.strictEqual(sample.discourse_focus.includes('concess'), true);
assert.strictEqual(sample.recovery_plan.priority, 'discourse');
assert.strictEqual(sample.recovery_plan.daily_drills.length, 1);
assert.ok(sample.week_alignment.includes('Semana 9'));

const minimal = parseFeedbackJson(`{"mistakes":[],"cefr_estimate":"A2"}`);
assert.ok(minimal);
assert.ok(Array.isArray(minimal.strengths));
assert.ok(minimal.recovery_plan);

console.log('✅ session feedback C1 parse tests passed');
