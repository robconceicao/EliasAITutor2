/**
 * Shared LLM access for F8 feedback + A.3 contextual translation.
 * Single failover chain — do not open parallel provider stacks elsewhere.
 *
 * Order: Groq → Gemini → Claude → DeepSeek (same as sessionFeedback F8).
 */
import Anthropic from '@anthropic-ai/sdk';
import { GoogleGenerativeAI } from '@google/generative-ai';

/**
 * @param {object} opts
 * @param {string} opts.system
 * @param {string} opts.user
 * @param {number} [opts.maxTokens=800]
 * @param {number} [opts.temperature=0.2]
 * @param {number} [opts.timeoutMs=10000] — hard cap per provider attempt (A.5)
 * @returns {Promise<string>}
 */
export async function callLlm({
  system,
  user,
  maxTokens = 800,
  temperature = 0.2,
  timeoutMs = 10_000,
} = {}) {
  const sys = system || '';
  const usr = user || '';
  if (!usr.trim()) return '';

  const errors = [];

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
        if (!res.ok) throw new Error(`Groq ${res.status}`);
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

  if (process.env.ANTHROPIC_API_KEY) {
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
      errors.push(`claude: ${e.message}`);
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
        if (!res.ok) throw new Error(`DeepSeek ${res.status}`);
        const data = await res.json();
        return (data.choices?.[0]?.message?.content || '').trim();
      });
    } catch (e) {
      errors.push(`deepseek: ${e.message}`);
    }
  }

  throw new Error(
    errors.length
      ? `No LLM succeeded (${errors.join('; ')})`
      : 'No LLM provider configured'
  );
}

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
