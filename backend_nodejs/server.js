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
      for await (const event of stream) {
        if (!estadoGeracao.ativo) break; // Abort if interrupted

        if (event.type === 'content_block_delta' && event.delta.text) {
          const chunkText = event.delta.text;
          respostaCompletaIA += chunkText;
          estadoGeracao.textoParcialIA += chunkText;
          
          context.push(chunkText);
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

app.get('/', (req, res) => {
  res.send('Elias AI Tutor Backend is running!');
});

server.listen(PORT, () => {
  console.log(`🚀 Servidor rodando na porta ${PORT}`);
});
