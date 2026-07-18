/**
 * Unit tests for A.3 pedagogical scaffold (no network).
 */
import assert from 'assert';
import {
  TRANSLATION_TIMEOUT_MS,
  formatScaffold,
} from './services/translationService.js';

assert.ok(TRANSLATION_TIMEOUT_MS >= 5000, 'translation timeout should be at least 5s');
assert.ok(TRANSLATION_TIMEOUT_MS <= 30_000, 'translation timeout should be reasonable');

const structured = formatScaffold(
  `EN: I went to the store yesterday.
PT: Fui à loja ontem.
SAY: Now say: I went to the store yesterday.
IPA: /wɛnt/`,
  'I went store'
);
assert.ok(structured.includes('EN:'), 'has EN rephrase');
assert.ok(structured.includes('SAY:'), 'has production cue');
assert.ok(!/^PT:/m.test(structured.split('\n')[0]), 'does not start as PT-only');

const freeform = formatScaffold('Just some free text without labels', 'Hello');
assert.ok(freeform.includes('EN:'), 'freeform becomes EN');
assert.ok(freeform.includes('SAY:'), 'freeform always has SAY');

console.log('✅ translation pedagogical scaffold tests passed');
console.log(`  TRANSLATION_TIMEOUT_MS=${TRANSLATION_TIMEOUT_MS}`);
