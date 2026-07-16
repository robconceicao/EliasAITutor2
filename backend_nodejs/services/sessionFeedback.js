/**
 * F8 — post-session report: PT-BR, pronunciation + discourse (C1 path).
 */
import { callLlm } from './llmClient.js';
import { updateSessionFeedback, getSession } from './programStore.js';

const FEEDBACK_PROMPT = `You are Elias, fluency coach for Roberto (program Fluência em Inglês em 6 Meses → functional C1).
Analyze the practice transcript. Reply ONLY in Brazilian Portuguese with strict JSON (no markdown, no extra text):
{
  "strengths":["ponto forte 1","2"],
  "mistakes":[
    {"said":"...","correct":"...","note":"...","ipa":"/.../","mouth_tip":"instrução de boca/língua/ar","severity":"critical|minor"}
  ],
  "better_phrases":["forma mais natural 1","2","3"],
  "pronunciation_focus":"schwa / linking / elisão / entonação — o que priorizar",
  "discourse_focus":"organização, fluência, registro, interação, range — o que priorizar (ou vazio se A1 muito básico)",
  "cefr_estimate":"A1|A2|B1|B2|C1",
  "week_alignment":"Semana N — objetivos cobertos / lacunas (se souber a semana no contexto)",
  "recovery_plan":{
    "priority":"pronunciation|discourse|grammar|fluency|vocabulary",
    "daily_drills":["drill ou tarefa 1","2"],
    "success_criteria":"como saber que melhorou"
  },
  "next_focus":"foco da próxima sessão (pode combinar pronúncia + discurso)",
  "motivation":"motivação curta, profissional, sem bajulação vazia"
}
Rules:
- Always fill strengths (1–3 items) — be honest and specific.
- mistakes max 5; prioritize meaning-blocking and advanced pronunciation / serious grammar.
- severity: "critical" = blocks meaning or holds progression; "minor" = slip.
- better_phrases max 3 — natural connected speech when relevant.
- pronunciation_focus always filled.
- discourse_focus: for B1+ comment on argument/organization/register/fluency; for A1 can be short habit note.
- recovery_plan always present; if session was strong, still give a light stretch plan.
- cefr_estimate: productive speaking level (if uneven, choose the lower productive band).
- No text outside the JSON.`;

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

function normalizeRecovery(raw) {
  if (!raw || typeof raw !== 'object') {
    return {
      priority: 'fluency',
      daily_drills: [],
      success_criteria: '',
    };
  }
  const drills = Array.isArray(raw.daily_drills)
    ? raw.daily_drills.map(String).slice(0, 5)
    : [];
  return {
    priority: String(raw.priority || 'fluency'),
    daily_drills: drills,
    success_criteria: String(raw.success_criteria || ''),
  };
}

export function parseFeedbackJson(raw) {
  if (!raw) return null;
  let s = raw.trim();
  const fence = s.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fence) s = fence[1].trim();
  const start = s.indexOf('{');
  const end = s.lastIndexOf('}');
  if (start === -1 || end === -1) return null;
  try {
    const obj = JSON.parse(s.slice(start, end + 1));
    if (!Array.isArray(obj.strengths)) obj.strengths = [];
    obj.strengths = obj.strengths.map(String).filter(Boolean).slice(0, 5);
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
    obj.better_phrases = obj.better_phrases.slice(0, 3).map(String);
    if (!obj.cefr_estimate) obj.cefr_estimate = 'A2';
    if (!obj.next_focus) obj.next_focus = '';
    if (!obj.pronunciation_focus) obj.pronunciation_focus = '';
    if (!obj.discourse_focus) obj.discourse_focus = '';
    if (!obj.week_alignment) obj.week_alignment = '';
    if (!obj.motivation) obj.motivation = '';
    obj.recovery_plan = normalizeRecovery(obj.recovery_plan);
    return obj;
  } catch {
    return null;
  }
}

async function callFeedbackLlm(promptText) {
  return callLlm({
    system: FEEDBACK_PROMPT,
    user: promptText,
    maxTokens: 1100,
    temperature: 0.2,
    timeoutMs: 18_000,
  });
}

/**
 * @param {string} sessionId
 * @param {string|array} historyOrTranscript
 * @param {{ week?: number, title?: string, level?: string }} [meta]
 */
export async function generateSessionFeedback(sessionId, historyOrTranscript, meta = {}) {
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

  const weekLine =
    meta.week != null
      ? `Contexto do programa: Semana ${meta.week}${meta.title ? ` — ${meta.title}` : ''}${meta.level ? ` (nível currículo ${meta.level})` : ''}.\n`
      : '';

  const userPayload = `${weekLine}Transcrição da sessão:\n${transcript}`;

  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const raw = await callFeedbackLlm(userPayload);
      const parsed = parseFeedbackJson(raw);
      if (parsed) {
        if (!parsed.week_alignment && meta.week != null) {
          parsed.week_alignment = `Semana ${meta.week}${meta.title ? ` — ${meta.title}` : ''}`;
        }
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
