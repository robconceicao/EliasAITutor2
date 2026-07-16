/**
 * A.3 — Contextual translation via shared LLM client (same stack as F8).
 * Discrete PT under the English bubble — never replaces the original.
 */
import { callLlm } from './llmClient.js';

const SYSTEM = `You are a bilingual English tutor assistant for the Elias app.
Translate the student's English message into natural, clear Brazilian Portuguese.
Preserve meaning, tone, and teaching intent (IPA symbols may stay as-is).
Reply with ONLY the Portuguese translation — no quotes, no preamble, no English.`;

/** D9: translation network wait — default 10s per provider inside callLlm. */
export const TRANSLATION_TIMEOUT_MS = Number(
  process.env.TRANSLATION_TIMEOUT_MS || 10_000
);

/**
 * @param {string} text English source
 * @returns {Promise<string>} pt-BR translation
 */
export async function translateToPtBr(text) {
  const src = (text || '').trim();
  if (!src) return '';

  const raw = await callLlm({
    system: SYSTEM,
    user: src,
    maxTokens: 500,
    temperature: 0.2,
    timeoutMs: TRANSLATION_TIMEOUT_MS,
  });

  // Strip accidental quotes / fences
  let out = (raw || '').trim();
  if (out.startsWith('```')) {
    out = out.replace(/^```(?:\w+)?\s*/, '').replace(/\s*```$/, '').trim();
  }
  if (
    (out.startsWith('"') && out.endsWith('"')) ||
    (out.startsWith("'") && out.endsWith("'"))
  ) {
    out = out.slice(1, -1).trim();
  }
  return out;
}
