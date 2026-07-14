/**
 * Unit tests for echo scoring (no network).
 * Run: node test_echo_score.js
 */
import assert from 'assert';
import {
  wordOverlapScore,
  durationHeuristicScore,
  scoreEchoAttempt,
} from './services/echoScoreService.js';

// Word overlap
assert.ok(wordOverlapScore('I want to go', 'I want to go') >= 90);
assert.ok(wordOverlapScore('I want to go', 'I want go') >= 50);
assert.ok(wordOverlapScore('hello world', '') < 30);

// Duration
assert.ok(durationHeuristicScore('one two three four five', 200) < 50);
assert.ok(durationHeuristicScore('one two three four five', 2000) >= 70);

// Full heuristic path (no audio / no GROQ required)
const r = await scoreEchoAttempt({
  reference: 'I want to go to America next summer',
  durationMs: 2800,
  focus: 'Schwa',
});
assert.strictEqual(r.ok, true);
assert.ok(r.score >= 20 && r.score <= 100);
assert.ok(r.method === 'heuristic');
assert.ok(String(r.feedback).length > 5);

console.log('✅ echo score unit tests passed', { score: r.score, method: r.method });
