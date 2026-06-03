import Anthropic from '@anthropic-ai/sdk';
import dotenv from 'dotenv';
dotenv.config();

const anthropic = new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY });

async function run() {
  try {
    console.log("Testing Anthropic with 'claude-3-5-sonnet-latest'...");
    const message = await anthropic.messages.create({
      model: 'claude-3-5-sonnet-latest',
      max_tokens: 50,
      messages: [{ role: 'user', content: 'Say hello in 3 words.' }]
    });
    console.log("Success! Response:", message.content[0].text);
  } catch (error) {
    console.error("Error with 'claude-3-5-sonnet-latest':", error.message);
  }

  try {
    console.log("Testing Anthropic with legacy 'claude-3-5-sonnet-20240620'...");
    const message = await anthropic.messages.create({
      model: 'claude-3-5-sonnet-20240620',
      max_tokens: 50,
      messages: [{ role: 'user', content: 'Say hello in 3 words.' }]
    });
    console.log("Success! Response:", message.content[0].text);
  } catch (error) {
    console.error("Error with 'claude-3-5-sonnet-20240620':", error.message);
  }
}

run();
