import express from 'express';
import http from 'http';
import { Server } from 'socket.io';
import Anthropic from '@anthropic-ai/sdk';
import Cartesia from '@cartesia/cartesia-js';
import mongoose from 'mongoose';
import cors from 'cors';
import dotenv from 'dotenv';

dotenv.config();

const app = express();
app.use(cors());

const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*' }
});

const PORT = process.env.PORT || 3000;

// Initialize APIs
const anthropic = new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY });
const cartesia = new Cartesia({ apiKey: process.env.CARTESIA_API_KEY });

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
  role: 'user', // Claude 3.5 requires system prompts via parameter, but keeping standard structure
  content: 'You are Elias, a helpful and patient English teacher. Keep your responses short (maximum 2-3 sentences) so we can maintain a dynamic spoken conversation.' 
};

// Handle WebSocket connections from Android App
io.on('connection', (socket) => {
  console.log('📱 Dispositivo conectado:', socket.id);

  let userIdAtual = socket.id; // Fallback to socket ID if no auth
  let estadoGeracao = { ativo: false, cartesiaContext: null, textoParcialIA: "" };
  let historicoMemoria = [SYSTEM_PROMPT];

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

    if (estadoGeracao.cartesiaContext) {
      try { estadoGeracao.cartesiaContext.no_more_inputs(); } catch (e) {}
    }
  });

  // 3. User Message Received
  socket.on('mensagem_usuario', async (textoUsuario) => {
    console.log(`💬 Usuário disse: ${textoUsuario}`);
    estadoGeracao.ativo = true;
    estadoGeracao.textoParcialIA = "";

    historicoMemoria.push({ role: 'user', content: textoUsuario });
    if (useMongo) {
      await Conversa.updateOne(
        { userId: userIdAtual }, 
        { $push: { mensagens: { role: 'user', content: textoUsuario } } }
      );
    }

    try {
      // Connect to Cartesia WebSocket
      const cartesiaSocket = cartesia.tts.websocket();
      await cartesiaSocket.connect();
      
      const context = cartesiaSocket.context({
        model_id: "sonic-english",
        voice: { mode: "id", id: "a0e99841-438c-4a64-b679-ae501e7d6091" }, // Default English Voice
        output_format: { container: "raw", encoding: "pcm_f32le", sample_rate: 44100 },
      });
      estadoGeracao.cartesiaContext = context;
      
      escutarRetornoCartesia(context, socket, estadoGeracao);

      // Map history to Anthropic format
      const mensagensParaClaude = historicoMemoria.map(m => ({ 
        role: m.role === 'system' ? 'user' : m.role, 
        content: m.content 
      }));

      // Call Anthropic in Streaming Mode
      const stream = await anthropic.messages.create({
        model: 'claude-3-5-sonnet-20240620',
        max_tokens: 250,
        messages: mensagensParaClaude,
        stream: true,
      });

      let respostaCompletaIA = "";
      let sentLength = 0;

      for await (const event of stream) {
        if (!estadoGeracao.ativo) break; // Abort if interrupted

        if (event.type === 'content_block_delta' && event.delta.text) {
          const chunkText = event.delta.text;
          respostaCompletaIA += chunkText;
          estadoGeracao.textoParcialIA += chunkText;
          
          // Stream only the text inside <RESPONSE> tags to Cartesia and user app
          const { text: newResponseText, newLength } = getNewResponseText(respostaCompletaIA, sentLength);
          if (newResponseText.length > 0) {
            context.push(newResponseText);
            socket.emit("texto_chunk", newResponseText);
            sentLength = newLength;
          }
        }
      }
      context.no_more_inputs();

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
    }
  });

  socket.on('disconnect', () => {
    console.log('❌ Dispositivo desconectado:', socket.id);
  });
});

// Function to stream audio chunks directly to Android
async function escutarRetornoCartesia(context, socket, estadoGeracao) {
  socket.emit("estado_ia", "falando");
  try {
    for await (const response of context.receive()) {
      if (!estadoGeracao.ativo) break;
      
      if (response.type === "chunk" && response.audio) {
        // Send base64 audio directly to Android (Base64 is safer for Socket.io than raw buffers across platforms)
        socket.emit("audio_chunk", response.audio);
      }
    }
  } catch (e) {
    console.error("Erro no retorno da Cartesia:", e);
  }
  socket.emit("estado_ia", "ociosa");
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
