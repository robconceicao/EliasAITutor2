import express from 'express';
import http from 'http';
import { Server } from 'socket.io';
import Anthropic from '@anthropic-ai/sdk';
import { GoogleGenerativeAI } from '@google/generative-ai';
import { encodePCMToOpus } from './audioEncoder.js';
import mongoose from 'mongoose';
import cors from 'cors';
import dotenv from 'dotenv';
import ws from 'ws';
import fs from 'fs';
import path from 'path';
import { registerBargeInHandler, registerGeneration, clearGeneration } from './bargeInHandler.js';
import { TurnTakingEngine, TURN_DECISION } from './turnTakingEngine.js';

// Load .env first (for any existing env vars)
dotenv.config();

// Load keys from local.properties if not already set (respect .env rule)
const localPropsPath = path.resolve('../local.properties');
if (fs.existsSync(localPropsPath)) {
  const lines = fs.readFileSync(localPropsPath, 'utf-8').split('\n');
  lines.forEach(line => {
    const trimmed = line.trim();
    if (trimmed && !trimmed.startsWith('#') && trimmed.includes('=')) {
      const [key, ...rest] = trimmed.split('=');
      const value = rest.join('=').trim();
      if (!process.env[key]) {
        process.env[key] = value;
      }
    }
  });
}

global.WebSocket = ws;

dotenv.config();

const app = express();
app.use(cors());

const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*' }
});

const PORT = process.env.PORT || 3000;

// Initialize APIs
const anthropic = process.env.ANTHROPIC_API_KEY ? new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY }) : null;
const googleAI = process.env.GEMINI_API_KEY ? new GoogleGenerativeAI(process.env.GEMINI_API_KEY) : null;

// MongoDB Connection (Optional - Graceful fallback to memory if not configured)
let useMongo = false;
if (process.env.MONGODB_URI) {
  mongoose.connect(process.env.MONGODB_URI)
    .then(() => {
      console.log('✅ Conectado ao MongoDB');
      useMongo = true;
    })
    .catch(err => console.error('❌ Erro no MongoDB:', err));
} else {
  console.log('⚠️ MONGODB_URI não configurada. Usando histórico em memória por sessão.');
}

// Schemas
const MensagemSchema = new mongoose.Schema({
  role: { type: String, enum: ['user', 'assistant', 'system'], required: true },
  content: { type: String, required: true },
  timestamp: { type: Date, default: Date.now }
});

const ConversaSchema = new mongoose.Schema({
  userId: { type: String, required: true, unique: true },
  mensagens: [MensagemSchema]
});
const Conversa = mongoose.model('Conversa', ConversaSchema);

const SYSTEM_PROMPT = { 
  role: 'system',
  content: `You are Elias, a master of the "Natural Approach" (Stephen Krashen's theory). Your goal is subconscious ACQUISITION, not conscious learning.

CORE PRINCIPLES:
1. INPUT HYPOTHESIS (i+1): Respond with English that is JUST ONE STEP above the student's current complexity. Keep it 90% understandable.
2. COMPELLING INPUT: Make the conversation so interesting (mystery, humor, drama) that the student forgets they are using a foreign language.
3. LOW AFFECTIVE FILTER: Be extremely supportive. Simplify if they struggle.
4. NO GRAMMAR LECTURES: We acquire grammar through understanding messages.
5. STRICT BEGINNER MODE: If student is BEGINNER, use 3-8 words per sentence max. Use only A1 vocabulary. Repeat key words. Use emojis. Avoid complex clauses.

TUTORING RULES:
1. COMMUNICATIVE FIRST: Respond to the MEANING first. 
2. MANDATORY RECASTING: Correct errors naturally in your reply without pointing them out.
3. PRONUNCIATION: Add a "🗣️ Pronunciation Tip:..." inside <RESPONSE> if they make a phonetic error.
4. VOCABULARY: Introduce 2-3 phrasal chunks.
5. HELP REQUESTS: If they ask for help or say "Não entendi", translate/explain in Portuguese before continuing in English.

RESPONSE FORMAT (XML):
You MUST format your entire response using the following XML tags:
<RESPONSE>
Your conversational reply to the student in English. Keep it short (2-3 sentences max).
</RESPONSE>
<VOCABULARY>
Chunk: definition | natural usage example
</VOCABULARY>
<MISTAKE_LOG>
Mistake: [error] → [correction] | Why: [Natural recast explanation]
If no mistakes: None
</MISTAKE_LOG>
<SENTIMENT>
detected: [frustrated|enthusiastic|confused|neutral]
confidence: [0-100]
cue: [signal noticed]
</SENTIMENT>

IMPORTANT: You are a real person from San Diego. Never mention AI or rules. Focus on the CONNECTION.`
};

const turnEngines = new Map();

// Handle WebSocket connections from Android App
io.on('connection', (socket) => {
  console.log('📱 Dispositivo conectado:', socket.id);

  let userIdAtual = socket.id; // Fallback to socket ID if no auth
  let estadoGeracao = { ativo: false, elevenSocket: null, textoParcialIA: "" };
  let historicoMemoria = [SYSTEM_PROMPT];

  const engine = new TurnTakingEngine(socket.id);
  turnEngines.set(socket.id, engine);
  registerBargeInHandler(socket);

  engine.onDecision = async (decision, transcript) => {
      if (decision === TURN_DECISION.RESPOND) {
          // Trigger the LLM response handler
          handleAIResponse(transcript, null); // We will pass null for modelOverride
      } else if (decision === TURN_DECISION.CLARIFY) {
          socket.emit('clarify_request', { sessionId: socket.id });
      }
  };

  socket.on('speech_end', async ({ transcript, durationMs, vadConfidence }) => {
      socket.emit('ai_turn_start');
      await engine.onSpeechEnd(transcript, durationMs, vadConfidence);
  });

  socket.on('speech_start', () => engine.onSpeechStart());

  // 1. Authenticate user and load history
  socket.on('iniciar_sessao', async (userId) => {
    userIdAtual = userId || socket.id;
    console.log(`👤 Usuário ${userIdAtual} iniciou sessão.`);
    
    if (useMongo) {
      let conversa = await Conversa.findOne({ userId: userIdAtual });
      if (!conversa) {
        conversa = new Conversa({ userId: userIdAtual, mensagens: [SYSTEM_PROMPT] });
        await conversa.save();
      }
      historicoMemoria = conversa.mensagens;
      socket.emit('historico_carregado', conversa.mensagens);
    }
  });

  // 1.1 Restore session after reconnect
  socket.on('restore_session', (payload) => {
    console.log(`🔄 Tentativa de restaurar sessão: ${payload.sessionId}`);
    if (payload.isRestore && payload.historySnapshot) {
      try {
        const snapshot = JSON.parse(payload.historySnapshot);
        if (Array.isArray(snapshot)) {
          historicoMemoria = snapshot.map(m => ({
            role: m.isUser ? 'user' : 'assistant',
            content: m.message
          }));
          if (historicoMemoria.length === 0 || historicoMemoria[0].role !== 'system') {
            historicoMemoria.unshift(SYSTEM_PROMPT);
          }
          console.log(`✅ Sessão restaurada com ${historicoMemoria.length} mensagens.`);
          socket.emit('session_restored', payload.sessionId);
        }
      } catch (e) {
        console.error("Erro ao restaurar sessão:", e);
      }
    }
  });

  // 2. User Barge-in (Interruption)
  socket.on('usuario_interrompeu', async () => {
    if (!estadoGeracao.ativo) return;
    console.log('🛑 Usuário interrompeu a IA.');
    estadoGeracao.ativo = false;
    
    const textoFalado = estadoGeracao.textoParcialIA.trim();
    if (textoFalado.length > 0) {
      historicoMemoria.push({ role: 'assistant', content: textoFalado });
      if (useMongo) {
        await Conversa.updateOne(
          { userId: userIdAtual },
          { $push: { mensagens: { role: 'assistant', content: textoFalado } } }
        );
      }
      estadoGeracao.textoParcialIA = "";
    }

    if (estadoGeracao.elevenSocket && estadoGeracao.elevenSocket.readyState === WebSocket.OPEN) {
      try {
        estadoGeracao.elevenSocket.close();
      } catch (e) {}
    }
  });

  // 3. User Message Received
  socket.on('mensagem_usuario', async (textoUsuario, modelOverride) => {
      await handleAIResponse(textoUsuario, modelOverride);
  });

  async function handleAIResponse(textoUsuario, modelOverride) {
    console.log(`💬 Usuário disse: ${textoUsuario} | Modelo sugerido: ${modelOverride || 'nenhum'}`);
    estadoGeracao.ativo = true;
    estadoGeracao.textoParcialIA = "";
    const seqTracker = { val: 0 };

    historicoMemoria.push({ role: 'user', content: textoUsuario });
    if (useMongo) {
      await Conversa.updateOne(
        { userId: userIdAtual }, 
        { $push: { mensagens: { role: 'user', content: textoUsuario } } }
      );
    }

    try {
      // Registrar os AbortControllers para Barge-in (TurnTaking engine cancel)
      const llmAbort = new AbortController();
      const ttsAbort = new AbortController();
      registerGeneration(socket.id, llmAbort, ttsAbort);

      // Connect to ElevenLabs WebSocket
      const voiceId = "pNInz6obpgDQGcFmaJcg"; // Adam
      const elevenUrl = `wss://api.elevenlabs.io/v1/text-to-speech/${voiceId}/stream-input?model_id=eleven_flash_v2_5`;
      
      const elevenSocket = new WebSocket(elevenUrl);
      
      // Wait for socket to open
      await new Promise((resolve, reject) => {
          elevenSocket.on('open', resolve);
          elevenSocket.on('error', reject);
      });

      // Send initial configuration
      const initMessage = {
          "text": " ",
          "voice_settings": { "stability": 0.5, "similarity_boost": 0.8 },
          "xi_api_key": process.env.ELEVENLABS_API_KEY || ""
      };
      elevenSocket.send(JSON.stringify(initMessage));

      estadoGeracao.elevenSocket = elevenSocket;
      
      escutarRetornoElevenLabs(elevenSocket, socket, estadoGeracao, seqTracker);

      // We should check llmAbort.signal.aborted during generation loop

      let modelToUse = modelOverride || process.env.DEFAULT_LLM || 'claude';
      
      let modelsToTry = [];
      if (modelToUse === 'claude') {
        modelsToTry = ['groq', 'gemini', 'deepseek', 'claude'];
      } else if (modelToUse === 'gemini') {
        modelsToTry = ['groq', 'claude', 'deepseek', 'gemini'];
      } else if (modelToUse === 'groq') {
        modelsToTry = ['groq', 'claude', 'gemini', 'deepseek'];
      } else {
        modelsToTry = ['groq', 'claude', 'gemini', 'deepseek'];
      }

      let respostaCompletaIA = "";
      let sentLength = 0;

      const handleChunk = (chunkText) => {
        if (!estadoGeracao.ativo || llmAbort.signal.aborted) return;
        respostaCompletaIA += chunkText;
        estadoGeracao.textoParcialIA += chunkText;
        
        // Stream only the text inside <RESPONSE> tags to ElevenLabs and user app
        const { text: newResponseText, newLength } = getNewResponseText(respostaCompletaIA, sentLength);
        if (newResponseText.length > 0) {
          if (elevenSocket.readyState === WebSocket.OPEN) {
            elevenSocket.send(JSON.stringify({ "text": newResponseText, "try_trigger_generation": true }));
          }
          socket.emit("texto_chunk", newResponseText);
          sentLength = newLength;
        }
      };

      let success = false;
      let lastError = null;

      for (const model of modelsToTry) {
        if (!estadoGeracao.ativo) break;
        try {
          console.log(`🤖 Tentando inteligência: ${model.toUpperCase()}`);
          if (model === 'claude') {
            if (!process.env.ANTHROPIC_API_KEY || !anthropic) {
              throw new Error("Anthropic API key is not configured or client is null");
            }
            const mensagensParaClaude = historicoMemoria
              .filter(m => m.role !== 'system')
              .map(m => ({ 
                role: m.role, 
                content: m.content 
              }));

            const stream = await anthropic.messages.create({
              model: 'claude-3-5-sonnet-20240620',
              max_tokens: 250,
              system: SYSTEM_PROMPT.content,
              messages: mensagensParaClaude,
              stream: true,
            });

            for await (const event of stream) {
              if (!estadoGeracao.ativo || llmAbort.signal.aborted) break;
              if (event.type === 'content_block_delta' && event.delta.text) {
                handleChunk(event.delta.text);
              }
            }
            success = true;
            break;
          } else if (model === 'gemini') {
            if (!process.env.GEMINI_API_KEY || !googleAI) {
              throw new Error("Gemini API key is not configured or client is null");
            }
            const geminiHistory = historicoMemoria
              .filter(m => m.role !== 'system')
              .map(m => ({
                role: m.role === 'assistant' ? 'model' : 'user',
                parts: [{ text: m.content }]
              }));

            const geminiModel = googleAI.getGenerativeModel({ 
              model: "gemini-1.5-flash",
              systemInstruction: SYSTEM_PROMPT.content
            });

            const result = await geminiModel.generateContentStream({
              contents: geminiHistory
            });

            for await (const chunk of result.stream) {
              if (!estadoGeracao.ativo || llmAbort.signal.aborted) break;
              const chunkText = chunk.text();
              if (chunkText) {
                handleChunk(chunkText);
              }
            }
            success = true;
            break;
          } else if (model === 'deepseek') {
            if (!process.env.DEEPSEEK_API_KEY) {
              throw new Error("DeepSeek API key is not configured");
            }
            const formattedMessages = [
              { role: 'system', content: SYSTEM_PROMPT.content },
              ...historicoMemoria.filter(m => m.role !== 'system').map(m => ({
                role: m.role === 'assistant' ? 'assistant' : 'user',
                content: m.content
              }))
            ];

            const response = await fetch('https://api.deepseek.com/chat/completions', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${process.env.DEEPSEEK_API_KEY}`
              },
              body: JSON.stringify({
                model: 'deepseek-chat',
                messages: formattedMessages,
                stream: true
              })
            });

            if (!response.ok) {
              const errText = await response.text();
              throw new Error(`DeepSeek API error: ${response.status} - ${errText}`);
            }

            const reader = response.body;
            for await (const chunk of reader) {
              if (!estadoGeracao.ativo || llmAbort.signal.aborted) break;
              const text = Buffer.isBuffer(chunk) ? chunk.toString('utf-8') : Buffer.from(chunk).toString('utf-8');
              const lines = text.split('\n');
              for (const line of lines) {
                if (line.trim().startsWith('data: ')) {
                  const jsonStr = line.trim().substring(6);
                  if (jsonStr === '[DONE]') break;
                  try {
                    const data = JSON.parse(jsonStr);
                    const delta = data.choices?.[0]?.delta?.content || "";
                    if (delta) {
                      handleChunk(delta);
                    }
                  } catch (e) {}
                }
              }
            }
            success = true;
            break;
          } else if (model === 'groq') {
            if (!process.env.GROQ_API_KEY) {
              throw new Error("Groq API key is not configured");
            }
            // Prepare formatted messages for Groq
            const formattedMessages = [
              { role: 'system', content: SYSTEM_PROMPT.content },
              ...historicoMemoria.filter(m => m.role !== 'system').map(m => ({
                role: m.role === 'assistant' ? 'assistant' : 'user',
                content: m.content
              }))
            ];
            const groqResponse = await fetch('https://api.groq.com/openai/v1/chat/completions', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${process.env.GROQ_API_KEY}`
              },
              body: JSON.stringify({
                model: 'llama-3.3-70b-versatile',
                messages: formattedMessages,
                stream: true
              })
            });

            if (!groqResponse.ok) {
              const errText = await groqResponse.text();
              throw new Error(`Groq API error: ${groqResponse.status} - ${errText}`);
            }

            const groqReader = groqResponse.body;
            for await (const chunk of groqReader) {
              if (!estadoGeracao.ativo) break;
              const text = Buffer.isBuffer(chunk) ? chunk.toString('utf-8') : Buffer.from(chunk).toString('utf-8');
              const lines = text.split('\n');
              for (const line of lines) {
                if (line.trim().startsWith('data: ')) {
                  const jsonStr = line.trim().substring(6);
                  if (jsonStr === '[DONE]') break;
                  try {
                    const data = JSON.parse(jsonStr);
                    const delta = data.choices?.[0]?.delta?.content || "";
                    if (delta) {
                      handleChunk(delta);
                    }
                  } catch (e) {}
                }
              }
            }
            success = true;
            break;
          } // end else if groq
        } catch (err) {
          console.warn(`⚠️ Modelo ${model.toUpperCase()} falhou: ${err.message}. Tentando próximo...`);
          lastError = err;
        }
      }

      if (!success) {
        throw lastError || new Error("Todos os modelos de linguagem falharam na geração");
      }

      if (elevenSocket.readyState === WebSocket.OPEN) {
        elevenSocket.send(JSON.stringify({ "text": "" })); // send empty string to close generation
      }

      // Save complete response if not interrupted
      if (estadoGeracao.ativo) {
        historicoMemoria.push({ role: 'assistant', content: respostaCompletaIA });
        if (useMongo) {
          await Conversa.updateOne(
            { userId: userIdAtual }, 
            { $push: { mensagens: { role: 'assistant', content: respostaCompletaIA } } }
          );
        }

        // Send final parsed message object (UiChatBubble structure) to Android
        const parsed = parseClaudeResponse(respostaCompletaIA);
        socket.emit("mensagem_ia", parsed);
      }

    } catch (error) {
      console.error("❌ Erro no fluxo principal:", error);
      socket.emit("erro_backend", error.message);
    } finally {
      clearGeneration(socket.id);
    }
  }

  socket.on('disconnect', () => {
    console.log('❌ Dispositivo desconectado:', socket.id);
  });
});

// Function to stream audio chunks directly to Android (encodes PCM to Opus frames)
function escutarRetornoElevenLabs(elevenSocket, socket, estadoGeracao, seqTracker) {
  socket.emit("estado_ia", "falando");
  
  elevenSocket.on("message", (msgStr) => {
    if (!estadoGeracao.ativo) return;
    try {
      const msg = JSON.parse(msgStr);
      if (msg.audio) {
        const pcmBuffer = Buffer.from(msg.audio, 'base64');
        const opusFrames = encodePCMToOpus(pcmBuffer);
        opusFrames.forEach((frame) => {
          socket.emit("audio_opus_frame", {
            frame: frame.toString("base64"),
            seq: seqTracker.val++,
            ts: Date.now()
          });
        });
      }
      if (msg.isFinal) {
        socket.emit("estado_ia", "ociosa");
      }
    } catch (e) {
      console.error("Erro processando retorno ElevenLabs:", e);
    }
  });
}

// Helper to extract the content inside <RESPONSE> tags as it streams
function getNewResponseText(accumulated, sentLength) {
  const openTag = "<RESPONSE>";
  const closeTag = "</RESPONSE>";
  
  const openIdx = accumulated.toUpperCase().indexOf(openTag);
  if (openIdx === -1) {
    return { text: "", newLength: 0 };
  }
  
  const startIdx = openIdx + openTag.length;
  const closeIdx = accumulated.toUpperCase().indexOf(closeTag, startIdx);
  
  let content = "";
  if (closeIdx === -1) {
    content = accumulated.substring(startIdx);
  } else {
    content = accumulated.substring(startIdx, closeIdx);
  }
  
  if (content.length > sentLength) {
    const newText = content.substring(sentLength);
    return { text: newText, newLength: content.length };
  }
  
  return { text: "", newLength: sentLength };
}

// Helper to parse the full XML response from Claude at the end
function parseClaudeResponse(raw) {
  const getTag = (name) => {
    const regex = new RegExp(`<${name}>([\\s\\S]*?)</${name}>`, 'i');
    const match = raw.match(regex);
    return match ? match[1].trim() : "";
  };

  const response = getTag("RESPONSE") || raw;
  const vocabRaw = getTag("VOCABULARY");
  const vocabulary = vocabRaw.split('\n').map(l => l.trim()).filter(l => l.length > 0);

  const mistakeRaw = getTag("MISTAKE_LOG");
  const mistakes = mistakeRaw.split('\n')
    .map(l => l.trim())
    .filter(l => l.length > 0 && l.toLowerCase() !== "none")
    .map(line => {
      const body = line.replace(/^Mistake\s*\d+:\s*/i, "");
      if (body.includes("→")) {
        const parts = body.split("→");
        const left = parts[0].trim();
        const rightPart = parts[1] || "";
        let right = rightPart;
        let rule = "";
        if (rightPart.includes("| Rule:")) {
          const ruleParts = rightPart.split("| Rule:");
          right = ruleParts[0].trim();
          rule = ruleParts[1].trim();
        }
        return { wrong: left, right: right, rule: rule, raw: line };
      }
      return { wrong: "", right: "", rule: "", raw: line };
    });

  const sentBlock = getTag("SENTIMENT");
  const detectedMatch = sentBlock.match(/detected:\s*(\w+)/i);
  const detected = detectedMatch ? detectedMatch[1].toLowerCase() : "neutral";

  const confidenceMatch = sentBlock.match(/confidence:\s*(\d+)/i);
  const confidence = confidenceMatch ? parseInt(confidenceMatch[1], 10) : 50;

  const cueMatch = sentBlock.match(/cue:\s*([\s\S]+)/i);
  const cue = cueMatch ? cueMatch[1].trim() : "";

  return {
    message: response,
    isUser: false,
    vocabulary,
    mistakes,
    sentiment: detected,
    sentimentCue: cue,
    sentimentConfidence: confidence
  };
}

app.get('/', (req, res) => {
  res.send('Elias AI Tutor Backend is running!');
});

server.listen(PORT, () => {
  console.log(`🚀 Servidor rodando na porta ${PORT}`);
});
