/**
 * Unit tests for A.3 translation helpers (no network).
 */
import assert from 'assert';
import { TRANSLATION_TIMEOUT_MS } from './services/translationService.js';

assert.ok(TRANSLATION_TIMEOUT_MS >= 5000, 'translation timeout should be at least 5s');
assert.ok(TRANSLATION_TIMEOUT_MS <= 30_000, 'translation timeout should be reasonable');

// Pure request detection is on Android; backend only needs empty handling shape
assert.strictEqual(typeof TRANSLATION_TIMEOUT_MS, 'number');

console.log('✅ translation service config tests passed');
console.log(`  TRANSLATION_TIMEOUT_MS=${TRANSLATION_TIMEOUT_MS}`);
