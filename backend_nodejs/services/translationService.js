/**
 * A.3 / audit fix — pedagogical scaffold (not a pure machine translator).
 * Returns structured help: EN rephrase + optional short PT + production cue + IPA.
 * Never PT-only — reduces L1 dependence.
 */
import { callLlm } from './llmClient.js';

const SYSTEM = `You are Elias's pedagogical scaffold for an English learner (Roberto, Brazilian Portuguese L1).
The student did not fully understand an English utterance. You help WITHOUT creating translation dependence.

Reply with EXACTLY this structure (labels in English, content as specified):

EN: <one simpler English rephrase, i+1, 1–2 short sentences. Same meaning.>
PT: <OPTIONAL one short Brazilian Portuguese gloss ONLY if meaning would still be blocked. Max 12 words. If not needed write: —>
SAY: <one English production prompt for the student, e.g. "Now say: …" or a fill-in>
IPA: <General American IPA for the KEY difficult word/phrase only, or — if not pronunciation-related>

Rules:
- Never reply with Portuguese alone.
- Prefer EN rephrase over PT gloss.
- Keep total under ~80 words.
- IPA only for the key item (AmE), not the whole paragraph.
- No markdown fences, no preamble outside the four labels.`;

export const TRANSLATION_TIMEOUT_MS = Number(
  process.env.TRANSLATION_TIMEOUT_MS || 10_000
);

/**
 * @param {string} text English source (Elias or student utterance)
 * @returns {Promise<string>} scaffold text for UI under the bubble
 */
export async function translateToPtBr(text) {
  const src = (text || '').trim();
  if (!src) return '';

  const raw = await callLlm({
    system: SYSTEM,
    user: `English to scaffold:\n"""${src.slice(0, 1200)}"""`,
    maxTokens: 350,
    temperature: 0.25,
    timeoutMs: TRANSLATION_TIMEOUT_MS,
  });

  return formatScaffold(raw, src);
}

/** Normalize model output into a clean multi-line scaffold. */
export function formatScaffold(raw, fallbackEn = '') {
  let out = (raw || '').trim();
  if (out.startsWith('```')) {
    out = out.replace(/^```(?:\w+)?\s*/, '').replace(/\s*```$/, '').trim();
  }

  const lines = { EN: '', PT: '', SAY: '', IPA: '' };
  for (const line of out.split('\n')) {
    const m = line.match(/^\s*(EN|PT|SAY|IPA)\s*:\s*(.*)$/i);
    if (m) {
      const key = m[1].toUpperCase();
      lines[key] = (m[2] || '').trim();
    }
  }

  // If model ignored structure, treat whole text as EN rephrase
  if (!lines.EN && !lines.PT && !lines.SAY) {
    lines.EN = out || fallbackEn;
    lines.SAY = fallbackEn
      ? `Now say in English (your words): key idea from the message.`
      : 'Now say it in English.';
    lines.PT = '—';
    lines.IPA = '—';
  }

  if (!lines.EN) lines.EN = fallbackEn || '—';
  if (!lines.PT) lines.PT = '—';
  if (!lines.SAY) lines.SAY = 'Now say it in English.';
  if (!lines.IPA) lines.IPA = '—';

  // Never allow PT-only effective response
  const parts = [
    `EN: ${lines.EN}`,
    lines.PT && lines.PT !== '—' ? `PT: ${lines.PT}` : null,
    `SAY: ${lines.SAY}`,
    lines.IPA && lines.IPA !== '—' ? `IPA: ${lines.IPA}` : null,
  ].filter(Boolean);

  return parts.join('\n');
}
