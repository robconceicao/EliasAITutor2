/**
 * Echo Mode scoring (Task Final):
 * 1) Transcribe student audio with Groq Whisper when available
 * 2) Score reference vs transcript via LLM (or word-overlap fallback)
 * 3) Duration heuristic when no audio / no ASR
 */

const WHISPER_MODEL = process.env.GROQ_WHISPER_MODEL || 'whisper-large-v3';
const CHAT_MODEL = process.env.GROQ_ECHO_MODEL || 'llama-3.3-70b-versatile';

function normalizeWords(text) {
  return String(text || '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s']/gu, ' ')
    .split(/\s+/)
    .filter(Boolean);
}

/**
 * Word-level accuracy 0–100 (Jaccard-ish coverage of reference words).
 */
export function wordOverlapScore(reference, hypothesis) {
  const ref = normalizeWords(reference);
  const hyp = normalizeWords(hypothesis);
  if (!ref.length) return 50;
  if (!hyp.length) return 15;
  const hypSet = new Set(hyp);
  let hit = 0;
  for (const w of ref) {
    if (hypSet.has(w)) hit += 1;
  }
  const coverage = hit / ref.length;
  // Penalize heavy extra words a bit
  const extra = Math.max(0, hyp.length - ref.length) / Math.max(ref.length, 1);
  const raw = coverage * 100 - extra * 12;
  return Math.round(Math.max(10, Math.min(98, raw)));
}

/**
 * Duration vs expected speaking time (learner pace ~320ms/word).
 */
export function durationHeuristicScore(reference, durationMs) {
  const words = normalizeWords(reference).length || 1;
  const expectedMs = Math.min(14_000, Math.max(900, words * 320 + 450));
  const d = Number(durationMs) || 0;
  if (d <= 0) return 55;
  if (d < 400) return 32;
  const ratio = d / expectedMs;
  if (ratio < 0.35) return 42;
  if (ratio < 0.55) return 58;
  if (ratio <= 1.4) return 84;
  if (ratio <= 2.0) return 72;
  return 54;
}

function coachingLine(focus) {
  const f = focus || 'Shadowing';
  const tips = {
    IPA: 'Check each IPA target sound carefully.',
    Shadowing: 'Match Elias’s pace and melody more closely.',
    Schwa: 'Weaken unstressed syllables toward /ə/.',
    Linking: 'Connect final consonants to following vowels.',
    Elisão: 'Allow natural reductions (wanna, gonna) where appropriate.',
    Entonação: 'Use falling statement intonation and stress content words.',
  };
  return tips[f] || 'Imitate rhythm, stress, and connected speech.';
}

async function transcribeWithGroq(audioBase64, mimeType = 'audio/mp4') {
  const key = process.env.GROQ_API_KEY;
  if (!key) throw new Error('GROQ_API_KEY missing');

  const buf = Buffer.from(audioBase64, 'base64');
  if (buf.length < 200) throw new Error('audio too small');
  if (buf.length > 4 * 1024 * 1024) throw new Error('audio too large');

  const ext =
    mimeType.includes('wav')
      ? 'wav'
      : mimeType.includes('webm')
        ? 'webm'
        : mimeType.includes('ogg')
          ? 'ogg'
          : 'm4a';

  const form = new FormData();
  form.append(
    'file',
    new Blob([buf], { type: mimeType || 'audio/mp4' }),
    `echo.${ext}`
  );
  form.append('model', WHISPER_MODEL);
  form.append('language', 'en');
  form.append('response_format', 'json');
  form.append('temperature', '0');

  const res = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}` },
    body: form,
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => '');
    throw new Error(`Whisper ${res.status}: ${errText.slice(0, 180)}`);
  }
  const data = await res.json();
  return String(data.text || '').trim();
}

async function scoreWithLlm(reference, transcript, focus) {
  const system = `You are an English pronunciation coach for Brazilian learners (General American).
Score how well the student echoed the reference phrase (content + likely pronunciation proxies from wording).
Reply ONLY with JSON: {"score":0-100,"feedback":"one short sentence in Portuguese with a tip"}`;

  const user = `Reference: "${reference}"
Student said: "${transcript}"
Today's focus: ${focus || 'Shadowing'}
Be fair: minor ASR errors shouldn't destroy a good attempt.`;

  if (process.env.GROQ_API_KEY) {
    const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${process.env.GROQ_API_KEY}`,
      },
      body: JSON.stringify({
        model: CHAT_MODEL,
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: user },
        ],
        temperature: 0.2,
        max_tokens: 120,
      }),
    });
    if (res.ok) {
      const data = await res.json();
      const raw = (data.choices?.[0]?.message?.content || '').trim();
      return parseScoreJson(raw);
    }
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
          { role: 'system', content: system },
          { role: 'user', content: user },
        ],
        temperature: 0.2,
        max_tokens: 120,
      }),
    });
    if (res.ok) {
      const data = await res.json();
      const raw = (data.choices?.[0]?.message?.content || '').trim();
      return parseScoreJson(raw);
    }
  }

  throw new Error('No LLM for echo scoring');
}

function parseScoreJson(raw) {
  const cleaned = String(raw || '')
    .replace(/^```json/i, '')
    .replace(/^```/, '')
    .replace(/```$/, '')
    .trim();
  try {
    const obj = JSON.parse(cleaned);
    const score = Math.max(0, Math.min(100, parseInt(obj.score, 10) || 60));
    const feedback = String(obj.feedback || 'Continue praticando!').slice(0, 280);
    return { score, feedback };
  } catch {
    const m = cleaned.match(/(\d{1,3})/);
    return {
      score: m ? Math.min(100, parseInt(m[1], 10)) : 60,
      feedback: 'Continue praticando o eco com Elias.',
    };
  }
}

/**
 * @param {{ reference: string, audioBase64?: string, mimeType?: string, durationMs?: number, focus?: string }} opts
 */
export async function scoreEchoAttempt(opts = {}) {
  const reference = String(opts.reference || '').trim();
  const focus = opts.focus || '';
  const durationMs = Number(opts.durationMs) || 0;
  let transcript = '';
  let method = 'heuristic';

  if (!reference) {
    return {
      ok: false,
      error: 'empty_reference',
      score: 0,
      feedback: '',
      transcript: '',
      method,
    };
  }

  if (opts.transcript && String(opts.transcript).trim()) {
    transcript = String(opts.transcript).trim();
    method = 'provided';
  } else if (opts.audioBase64 && process.env.GROQ_API_KEY) {
    try {
      transcript = await transcribeWithGroq(
        opts.audioBase64,
        opts.mimeType || 'audio/mp4'
      );
      if (transcript) method = 'whisper';
    } catch (e) {
      console.warn('[echoScore] ASR failed:', e.message);
    }
  }

  if (transcript) {
    try {
      const llm = await scoreWithLlm(reference, transcript, focus);
      // Blend lightly with word overlap so empty ASR can't score 100 easily
      const overlap = wordOverlapScore(reference, transcript);
      const score = Math.round(llm.score * 0.75 + overlap * 0.25);
      return {
        ok: true,
        score: Math.max(0, Math.min(100, score)),
        feedback: `${llm.feedback} ${coachingLine(focus)}`.trim(),
        transcript,
        method: method === 'whisper' ? 'whisper+llm' : 'llm',
      };
    } catch (e) {
      console.warn('[echoScore] LLM score failed:', e.message);
      const score = wordOverlapScore(reference, transcript);
      return {
        ok: true,
        score,
        feedback: `Você disse: “${transcript}”. ${coachingLine(focus)}`,
        transcript,
        method: 'overlap',
      };
    }
  }

  // No ASR — duration heuristic
  let score = durationHeuristicScore(reference, durationMs);
  if (durationMs > 0) {
    const words = normalizeWords(reference).length || 1;
    const expected = words * 320 + 450;
    const ratio = durationMs / expected;
    if (ratio >= 0.7 && ratio <= 1.35) score = Math.min(94, score + 6);
  }

  return {
    ok: true,
    score,
    feedback: `Sem transcrição automática — score por ritmo. Ouça o Echo e compare. ${coachingLine(focus)}`,
    transcript: '',
    method: 'heuristic',
  };
}
