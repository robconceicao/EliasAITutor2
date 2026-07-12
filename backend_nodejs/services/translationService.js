/**
 * Contextual translation via existing LLM providers (Task Final v1.0).
 * Returns natural Brazilian Portuguese — never machine-literal only.
 */

const SYSTEM = `You are a bilingual English tutor assistant.
Translate the user's English message into natural, clear Brazilian Portuguese.
Preserve meaning, tone, and teaching intent (IPA symbols may stay as-is).
Reply with ONLY the Portuguese translation — no quotes, no preamble.`;

export async function translateToPtBr(text) {
  const src = (text || '').trim();
  if (!src) return '';

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
          { role: 'system', content: SYSTEM },
          { role: 'user', content: src },
        ],
        temperature: 0.2,
        max_tokens: 500,
      }),
    });
    if (res.ok) {
      const data = await res.json();
      return (data.choices?.[0]?.message?.content || '').trim();
    }
  }

  if (process.env.GEMINI_API_KEY) {
    const { GoogleGenerativeAI } = await import('@google/generative-ai');
    const googleAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
    const model = googleAI.getGenerativeModel({
      model: 'gemini-1.5-flash',
      systemInstruction: SYSTEM,
    });
    const result = await model.generateContent(src);
    return (result.response.text() || '').trim();
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
          { role: 'system', content: SYSTEM },
          { role: 'user', content: src },
        ],
        temperature: 0.2,
      }),
    });
    if (res.ok) {
      const data = await res.json();
      return (data.choices?.[0]?.message?.content || '').trim();
    }
  }

  throw new Error('No LLM available for translation');
}
