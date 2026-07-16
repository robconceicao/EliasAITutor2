/**
 * F8 — post-session correction report via shared LLM client (llmClient.js).
 */
import { callLlm } from './llmClient.js';
import { updateSessionFeedback, getSession } from './programStore.js';

const FEEDBACK_PROMPT = `You are Elias, fluency coach. Analyze this English practice transcript for Roberto.
Reply ONLY in Brazilian Portuguese with strict JSON (no markdown, no extra text):
{
  "mistakes":[
    {"said":"...","correct":"...","note":"...","ipa":"/.../","mouth_tip":"instrução de boca/língua/ar","severity":"critical|minor"}
  ],
  "better_phrases":["forma mais natural 1","2","3"],
  "pronunciation_focus":"feedback sobre redução vocálica (schwa), linked speech, elisão e entonação (↓↑↑↓↓↑)",
  "cefr_estimate":"A1|A2|B1|B2|C1",
  "next_focus":"foco da próxima sessão (Pronúncia Avançada Máxima: drill + técnica)",
  "motivation":"motivação personalizada curta"
}
Rules:
- mistakes max 5; prioritize advanced pronunciation (IPA, schwa, linking, elision, intonation) and serious grammar.
- severity: "critical" = blocks meaning / serious grammar or pronunciation that would hold the student back; "minor" = small slips.
- better_phrases max 3 — prefer natural connected-speech versions.
- Always fill pronunciation_focus (mention which of: reduction, linking, elision, intonation).
- No text outside the JSON.`;

/** ~8k tokens ≈ 32k chars conservative (D3) */
const MAX_TRANSCRIPT_CHARS = 32000;

function truncateTranscript(text) {
  if (!text || text.length <= MAX_TRANSCRIPT_CHARS) return text || '';
  return text.slice(-MAX_TRANSCRIPT_CHARS);
}

function formatHistory(messages) {
  return messages
    .filter((m) => m.role === 'user' || m.role === 'assistant')
    .map((m) => `${m.role === 'user' ? 'Student' : 'Tutor'}: ${m.content}`)
    .join('\n');
}

function parseFeedbackJson(raw) {
  if (!raw) return null;
  let s = raw.trim();
  const fence = s.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fence) s = fence[1].trim();
  const start = s.indexOf('{');
  const end = s.lastIndexOf('}');
  if (start === -1 || end === -1) return null;
  try {
    const obj = JSON.parse(s.slice(start, end + 1));
    if (!Array.isArray(obj.mistakes)) obj.mistakes = [];
    obj.mistakes = obj.mistakes.slice(0, 5).map((m) => {
      const sev = String(m?.severity || 'minor').toLowerCase();
      return {
        said: m?.said || '',
        correct: m?.correct || '',
        note: m?.note || '',
        ipa: m?.ipa || '',
        mouth_tip: m?.mouth_tip || m?.mouthTip || '',
        severity: sev === 'critical' ? 'critical' : 'minor',
      };
    });
    if (!Array.isArray(obj.better_phrases)) obj.better_phrases = [];
    obj.better_phrases = obj.better_phrases.slice(0, 3);
    if (!obj.cefr_estimate) obj.cefr_estimate = 'A2';
    if (!obj.next_focus) obj.next_focus = '';
    if (!obj.pronunciation_focus) obj.pronunciation_focus = '';
    if (!obj.motivation) obj.motivation = '';
    return obj;
  } catch {
    return null;
  }
}

async function callFeedbackLlm(promptText) {
  return callLlm({
    system: FEEDBACK_PROMPT,
    user: promptText,
    maxTokens: 800,
    temperature: 0.2,
    timeoutMs: 15_000,
  });
}

/**
 * Generate feedback; never throws to caller for session end path.
 * Retries once on non-JSON. Sets feedback_status ready|failed.
 */
export async function generateSessionFeedback(sessionId, historyOrTranscript) {
  await updateSessionFeedback(sessionId, null, 'pending');

  let transcript;
  if (typeof historyOrTranscript === 'string') {
    transcript = historyOrTranscript;
  } else if (Array.isArray(historyOrTranscript)) {
    transcript = formatHistory(historyOrTranscript);
  } else {
    transcript = '';
  }
  transcript = truncateTranscript(transcript);

  if (!transcript.trim()) {
    await updateSessionFeedback(sessionId, null, 'failed');
    return { feedback_status: 'failed', feedback_json: null };
  }

  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const raw = await callFeedbackLlm(transcript);
      const parsed = parseFeedbackJson(raw);
      if (parsed) {
        await updateSessionFeedback(sessionId, parsed, 'ready');
        return { feedback_status: 'ready', feedback_json: parsed };
      }
      console.warn(`[feedback] attempt ${attempt + 1}: non-JSON response`);
    } catch (e) {
      console.warn(`[feedback] attempt ${attempt + 1} failed:`, e.message);
    }
  }

  await updateSessionFeedback(sessionId, null, 'failed');
  return { feedback_status: 'failed', feedback_json: null };
}

export async function getSessionFeedback(sessionId) {
  const s = await getSession(sessionId);
  if (!s) return null;
  return {
    feedback_status: s.feedback_status,
    feedback_json: s.feedback_json,
  };
}
