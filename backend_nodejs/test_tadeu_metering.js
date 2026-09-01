import assert from 'node:assert/strict';
import { VoiceMinuteAccumulator } from './services/tadeuMetering.js';

const meter = new VoiceMinuteAccumulator('test-session');

let state = meter.addSpeech(59_999);
assert.equal(state.newlyCompleted, 0);
assert.equal(state.completedMinutes, 0);
assert.equal(state.remainderMs, 59_999);

state = meter.addSpeech(1);
assert.equal(state.newlyCompleted, 1);
assert.equal(state.completedMinutes, 1);
assert.equal(state.remainderMs, 0);
assert.equal(meter.idempotencyKey(1), 'voice:test-session:minute:1');

meter.markConsumed(1);
state = meter.addSpeech(30_000);
assert.equal(state.newlyCompleted, 0);
assert.equal(state.completedMinutes, 1);
assert.equal(state.remainderMs, 30_000);

state = meter.addSpeech(90_000);
assert.equal(state.newlyCompleted, 2);
assert.equal(state.completedMinutes, 3);
assert.equal(state.remainderMs, 0);

meter.markConsumed(2);
assert.equal(meter.consumedMinutes, 3);

const invalid = new VoiceMinuteAccumulator('invalid-input');
assert.equal(invalid.addSpeech(-10).totalSpeechMs, 0);
assert.equal(invalid.addSpeech(Number.NaN).totalSpeechMs, 0);

console.log('✅ Tadeu voice minute accumulator tests passed');
