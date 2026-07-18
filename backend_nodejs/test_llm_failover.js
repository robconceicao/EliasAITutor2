/**
 * Unit tests for LLM failover helpers (no network).
 */
import assert from 'assert';
import {
  isLlmBillingOrAuthError,
  preferredChatModelOrder,
  markClaudeUnavailable,
  shouldSkipClaude,
} from './services/llmClient.js';
import { questionSection } from './services/programStore.js';

assert.strictEqual(
  isLlmBillingOrAuthError(new Error('Your credit balance is too low')),
  true
);
assert.strictEqual(isLlmBillingOrAuthError(new Error('timeout')), false);

const order = preferredChatModelOrder('claude');
assert.deepStrictEqual(order.slice(0, 3), ['groq', 'gemini', 'deepseek']);
assert.ok(order.includes('claude') || shouldSkipClaude());

markClaudeUnavailable('test', 60_000);
assert.strictEqual(shouldSkipClaude(), true);
const order2 = preferredChatModelOrder(null);
assert.ok(!order2.includes('claude'));

assert.strictEqual(questionSection({ question: 'Letra com som /ái/:' }), 'pronunciation');
assert.strictEqual(questionSection({ question: 'Contração de "They are":' }), 'vocabulary');
assert.strictEqual(questionSection({ question: 'x', section: 'pronunciation' }), 'pronunciation');

console.log('✅ llm failover + quiz section tests passed');
