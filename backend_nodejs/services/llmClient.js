/**
 * Shared LLM access for F8 feedback + A.3 contextual translation + Echo phrases.
 * Single failover chain — do not open parallel provider stacks elsewhere.
 *
 * Order: Groq → Gemini → DeepSeek → Claude (Claude last — credit/billing risk).
 */
import Anthropic from '@anthropic-ai/sdk';
import { GoogleGenerativeAI } from '@google/generative-ai';

/** After Anthropic billing/credit failure, skip Claude until process restart or cooldown. */
let claudeSkippedUntil = 0;

/**
 * Detect Anthropic credit / billing / auth failures (HTTP 400 credit balance, 401, 403).
 * @param {unknown} err
 */
export function isLlmBillingOrAuthError(err) {
  const msg = String(err?.message || err || '').toLowerCase();
  const status = err?.status || err?.statusCode || err?.error?.status;
  if (status === 401 || status === 402 || status === 403) return true;
  return (
    msg.includes('credit balance') ||
    msg.includes('credit_balance') ||
    msg.includes('insufficient') ||
    msg.includes('billing') ||
    msg.includes('payment') ||
    msg.includes('quota') ||
    msg.includes('rate_limit') ||
    msg.includes('too many requests') ||
    msg.includes('401') ||
    msg.includes('402') ||
    msg.includes('403') ||
    (msg.includes('400') && (msg.includes('credit') || msg.includes('anthropic')))
  );
}

export function shouldSkipClaude() {
  return Date.now() < claudeSkippedUntil;
}

export function markClaudeUnavailable(reason = '', cooldownMs = 30 * 60 * 1000) {
  claudeSkippedUntil = Date.now() + cooldownMs;
  console.warn(
    `[llm] Claude skipped for ${Math.round(cooldownMs / 60000)}min — ${reason || 'billing/auth'}`
  );
}

/**
 * Preferred chat stream order for server.js (Claude never first).
 * @returns {string[]}
 */
export function preferredChatModelOrder(modelOverride) {
  const base = ['groq', 'gemini', 'deepseek', 'claude'];
  if (shouldSkipClaude()) {
    return base.filter((m) => m !== 'claude');
  }
  // If override names a preferred first try, put it first but keep Claude last
  const pref = String(modelOverride || process.env.DEFAULT_LLM || '')
    .toLowerCase()
    .trim();
  if (!pref || pref === 'claude') return base;
  const rest = base.filter((m) => m !== pref);
  if (base.includes(pref)) return [pref, ...rest.filter((m) => m !== 'claude'), 'claude'].filter(
    (m, i, a) => a.indexOf(m) === i && (m !== 'claude' || !shouldSkipClaude())
  );
  return base;
}

/**
 * @param {object} opts
 * @param {string} opts.system
 * @param {string} opts.user
 * @param {number} [opts.maxTokens=800]
 * @param {number} [opts.temperature=0.2]
 * @param {number} [opts.timeoutMs=10000] — hard cap per provider attempt (A.5)
 * @param {boolean} [opts.skipClaude=false]
 * @returns {Promise<string>}
 */
export async function callLlm({
  system,
  user,
  maxTokens = 800,
  temperature = 0.2,
  timeoutMs = 10_000,
  skipClaude = false,
} = {}) {
  const sys = system || '';
  const usr = user || '';
  if (!usr.trim()) return '';

  const errors = [];
  const tryClaude = !skipClaude && !shouldSkipClaude() && process.env.ANTHROPIC_API_KEY;

  if (process.env.GROQ_API_KEY) {
    try {
      return await withTimeout(timeoutMs, async () => {
        const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${process.env.GROQ_API_KEY}`,
          },
          body: JSON.stringify({
            model: 'llama-3.3-70b-versatile',
            messages: [
              { role: 'system', content: sys },
              { role: 'user', content: usr },
            ],
            temperature,
            max_tokens: maxTokens,
          }),
        });
        if (!res.ok) {
          const t = await res.text().catch(() => '');
          throw new Error(`Groq ${res.status}: ${t.slice(0, 120)}`);
        }
        const data = await res.json();
        return (data.choices?.[0]?.message?.content || '').trim();
      });
    } catch (e) {
      errors.push(`groq: ${e.message}`);
    }
  }

  if (process.env.GEMINI_API_KEY) {
    try {
      return await withTimeout(timeoutMs, async () => {
        const googleAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const model = googleAI.getGenerativeModel({
          model: 'gemini-1.5-flash',
          systemInstruction: sys,
        });
        const result = await model.generateContent(usr);
        return (result.response.text() || '').trim();
      });
    } catch (e) {
      errors.push(`gemini: ${e.message}`);
    }
  }

  if (process.env.DEEPSEEK_API_KEY) {
    try {
      return await withTimeout(timeoutMs, async () => {
        const res = await fetch('https://api.deepseek.com/chat/completions', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${process.env.DEEPSEEK_API_KEY}`,
          },
          body: JSON.stringify({
            model: 'deepseek-chat',
            messages: [
              { role: 'system', content: sys },
              { role: 'user', content: usr },
            ],
            temperature,
            max_tokens: maxTokens,
          }),
        });
        if (!res.ok) {
          const t = await res.text().catch(() => '');
          throw new Error(`DeepSeek ${res.status}: ${t.slice(0, 120)}`);
        }
        const data = await res.json();
        return (data.choices?.[0]?.message?.content || '').trim();
      });
    } catch (e) {
      errors.push(`deepseek: ${e.message}`);
    }
  }

  // Claude last — skip when credit balance / billing failed recently
  if (tryClaude) {
    try {
      return await withTimeout(timeoutMs, async () => {
        const anthropic = new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY });
        const msg = await anthropic.messages.create({
          model: 'claude-sonnet-4-6',
          max_tokens: maxTokens,
          system: sys,
          messages: [{ role: 'user', content: usr }],
          temperature,
        });
        return (msg.content?.[0]?.text || '').trim();
      });
    } catch (e) {
      if (isLlmBillingOrAuthError(e)) {
        markClaudeUnavailable(e.message);
      }
      errors.push(`claude: ${e.message}`);
    }
  } else if (process.env.ANTHROPIC_API_KEY && shouldSkipClaude()) {
    errors.push('claude: skipped (recent billing/credit failure)');
  }

  throw new Error(
    errors.length
      ? `No LLM succeeded (${errors.join('; ')})`
      : 'No LLM provider configured'
  );
}

/**
 * Generate one Echo Mode phrase + IPA via failover LLM (never blocks on Anthropic credit).
 * @returns {Promise<{ phrase: string, ipa: string, source: string }>}
 */
export async function generateEchoPhrase() {
  const system =
    'You are an English pronunciation coach for Brazilian learners (General American).';
  const user =
    'Generate ONE natural General American English sentence (10-18 words) for pronunciation shadowing. ' +
    'Include schwa/linking where natural. Reply ONLY with JSON: {"phrase":"...","ipa":"/.../"}';
  try {
    const raw = await callLlm({
      system,
      user,
      maxTokens: 120,
      temperature: 0.7,
      timeoutMs: 12_000,
      skipClaude: shouldSkipClaude(),
    });
    const cleaned = String(raw || '')
      .replace(/^```json/i, '')
      .replace(/^```/, '')
      .replace(/```$/, '')
      .trim();
    const obj = JSON.parse(cleaned);
    const phrase = String(obj.phrase || '').trim();
    const ipa = String(obj.ipa || '').trim();
    if (phrase.length >= 8) {
      return { phrase, ipa: ipa || '', source: 'llm' };
    }
  } catch (e) {
    console.warn('[llm] generateEchoPhrase failed:', e.message);
  }
  // Local bank fallback — never fail Echo Mode because of LLM credits
  const bank = ECHO_PHRASE_BANK;
  const pick = bank[Math.floor(Math.random() * bank.length)];
  return { ...pick, source: 'bank' };
}

export const ECHO_PHRASE_BANK = [
  {
    phrase: 'I want to go to America next summer.',
    ipa: '/aɪ ˈwɑnə ɡoʊ tə əˈmɛɹɪkə nɛkst ˈsʌmɚ/',
  },
  {
    phrase: 'Think about the weather before you leave the house.',
    ipa: '/θɪŋk əˈbaʊt ðə ˈwɛðɚ bɪˈfɔɹ ju liv ðə haʊs/',
  },
  {
    phrase: 'Could you put it on the table over there?',
    ipa: '/kʊd ju pʊt ɪt ɑn ðə ˈteɪbəl ˈoʊvɚ ðɛɹ/',
  },
  {
    phrase: 'I have to get up early tomorrow morning.',
    ipa: '/aɪ hæf tə ɡɛt ʌp ˈɝli təˈmɑɹoʊ ˈmɔɹnɪŋ/',
  },
  {
    phrase: 'What do you think about living in the city?',
    ipa: '/wʌd ju θɪŋk əˈbaʊt ˈlɪvɪŋ ɪn ðə ˈsɪti/',
  },
  {
    phrase: 'Please call me when you get a chance today.',
    ipa: '/pliz kɔl mi wɛn ju ɡɛt ə tʃæns təˈdeɪ/',
  },
  {
    phrase: 'I need a little bit of water right now.',
    ipa: '/aɪ nid ə ˈlɪɾəl bɪt əv ˈwɔtɚ raɪt naʊ/',
  },
  {
    phrase: 'That was a really good idea for the project.',
    ipa: '/ðæt wəz ə ˈɹɪli ɡʊd aɪˈdiə fɚ ðə ˈpɹɑdʒɛkt/',
  },
];

function withTimeout(ms, fn) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`LLM timeout after ${ms}ms`)), ms);
    Promise.resolve()
      .then(fn)
      .then((v) => {
        clearTimeout(t);
        resolve(v);
      })
      .catch((e) => {
        clearTimeout(t);
        reject(e);
      });
  });
}
