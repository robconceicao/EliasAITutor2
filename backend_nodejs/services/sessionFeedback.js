/**
 * F8 — post-session correction report via existing LLM providers.
 */
import Anthropic from '@anthropic-ai/sdk';
import { GoogleGenerativeAI } from '@google/generative-ai';
import { updateSessionFeedback, getSession } from './programStore.js';

const FEEDBACK_PROMPT = `You are Elias, fluency coach. Analyze this English practice transcript for Roberto.
Reply ONLY in Brazilian Portuguese with strict JSON (no markdown, no extra text):
{
  "mistakes":[
    {"said":"...","correct":"...","note":"...","ipa":"/.../","mouth_tip":"instrução de boca/língua/ar"}
  ],
  "better_phrases":["forma mais natural 1","2","3"],
  "pronunciation_focus":"feedback sobre redução vocálica (schwa), linked speech, elisão e entonação (↓↑↑↓↓↑)",
  "cefr_estimate":"A1|A2|B1|B2|C1",
  "next_focus":"foco da próxima sessão (Pronúncia Avançada Máxima: drill + técnica)",
  "motivation":"motivação personalizada curta"
}
Rules:
- mistakes max 5; prioritize advanced pronunciation (IPA, schwa, linking, elision, intonation) and serious grammar.
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
    obj.mistakes = obj.mistakes.slice(0, 5).map((m) => ({
      said: m?.said || '',
      correct: m?.correct || '',
      note: m?.note || '',
      ipa: m?.ipa || '',
      mouth_tip: m?.mouth_tip || m?.mouthTip || '',
    }));
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

async function callLlm(promptText) {
  // Prefer Groq (fast/cheap) → Gemini → Claude → DeepSeek
  if (process.env.GROQ_API_KEY) {
    const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${process.env.GROQ_API_KEY}`,
      },
      body: JSON.stringify({
        model: 'llama-3.3-70b-versatile',
        messages: [
          { role: 'system', content: FEEDBACK_PROMPT },
          { role: 'user', content: promptText },
        ],
        temperature: 0.2,
      }),
    });
    if (!res.ok) throw new Error(`Groq ${res.status}`);
    const data = await res.json();
    return data.choices?.[0]?.message?.content || '';
  }

  if (process.env.GEMINI_API_KEY) {
    const googleAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
    const model = googleAI.getGenerativeModel({
      model: 'gemini-1.5-flash',
      systemInstruction: FEEDBACK_PROMPT,
    });
    const result = await model.generateContent(promptText);
    return result.response.text();
  }

  if (process.env.ANTHROPIC_API_KEY) {
    const anthropic = new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY });
    const msg = await anthropic.messages.create({
      model: 'claude-sonnet-4-6',
      max_tokens: 800,
      system: FEEDBACK_PROMPT,
      messages: [{ role: 'user', content: promptText }],
    });
    return msg.content?.[0]?.text || '';
  }

  if (process.env.DEEPSEEK_API_KEY) {
    const res = await fetch('https://api.deepseek.com/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${process.env.DEEPSEEK_API_KEY}`,
      },
      body: JSON.stringify({
        model: 'deepseek-chat',
        messages: [
          { role: 'system', content: FEEDBACK_PROMPT },
          { role: 'user', content: promptText },
        ],
        temperature: 0.2,
      }),
    });
    if (!res.ok) throw new Error(`DeepSeek ${res.status}`);
    const data = await res.json();
    return data.choices?.[0]?.message?.content || '';
  }

  throw new Error('No LLM provider configured for feedback');
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
      const raw = await callLlm(transcript);
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
