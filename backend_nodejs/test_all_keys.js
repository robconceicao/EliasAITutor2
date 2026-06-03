import fs from 'fs';
import path from 'path';

function readLocalProperties() {
  const propsPath = path.resolve('../local.properties');
  if (!fs.existsSync(propsPath)) {
    console.error("local.properties not found!");
    return {};
  }
  const content = fs.readFileSync(propsPath, 'utf-8');
  const config = {};
  content.split('\n').forEach(line => {
    if (line.trim() && !line.trim().startsWith('#') && line.includes('=')) {
      const parts = line.split('=');
      const key = parts[0].trim();
      const value = parts.slice(1).join('=').trim();
      config[key] = value;
    }
  });
  return config;
}

const keys = readLocalProperties();

async function testGroq(apiKey, model) {
  if (!apiKey) {
    console.log("❌ Groq key not provided");
    return;
  }
  try {
    console.log(`Testing Groq API with model: ${model}...`);
    const resp = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKey}`
      },
      body: JSON.stringify({
        model: model,
        messages: [{ role: 'user', content: 'Say hello in 3 words.' }]
      })
    });
    const data = await resp.json();
    if (resp.ok) {
      console.log(`✅ Groq Success with ${model}! Response:`, data.choices[0].message.content);
      return true;
    } else {
      console.log(`❌ Groq Fail with ${model}:`, data.error?.message || JSON.stringify(data));
      return false;
    }
  } catch (err) {
    console.log(`❌ Groq Error with ${model}:`, err.message);
    return false;
  }
}

async function run() {
  const models = ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant', 'mixtral-8x7b-32768', 'gemma2-9b-it'];
  for (const model of models) {
    const success = await testGroq(keys.GROQ_API_KEY, model);
    if (success) break;
  }
}

run();
