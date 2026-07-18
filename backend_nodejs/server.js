import express from 'express';
import http from 'http';
import { Server } from 'socket.io';
import Anthropic from '@anthropic-ai/sdk';
import { GoogleGenerativeAI } from '@google/generative-ai';
import {
  createPcmInt16OpusEncoder,
  sampleRateFromOutputFormat,
  getOpusBackend,
} from './audioEncoder.js';
import mongoose from 'mongoose';
import cors from 'cors';
import dotenv from 'dotenv';
import ws from 'ws';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { registerBargeInHandler, registerGeneration, clearGeneration } from './bargeInHandler.js';
import { TurnTakingEngine, TURN_DECISION } from './turnTakingEngine.js';
import {
  buildSystemPrompt,
  DEFAULT_ELIAS_SYSTEM_PROMPT,
  phaseForWeek,
} from './services/promptBuilder.js';
import {
  setMongoEnabled,
  upsertWeeks,
  upsertQuizzes,
  getWeek,
  getProgramState,
} from './services/programStore.js';
import {
  resolveMainChatVoiceId,
  resolveFallbackVoiceId,
  openTtsWebSocket,
  openTtsWebSocketWithFallback,
  createFirstAudioWatchdog,
  FIRST_AUDIO_BYTE_TIMEOUT_MS,
  STREAM_MODEL_ID,
  STREAM_OUTPUT_FORMAT,
  ensureElevenLabsKeyEnv,
  hasElevenLabsKey,
  resolveApiKeySource,
  synthesizePcmRest,
} from './services/elevenLabsClient.js';
import programRoutes from './routes/programRoutes.js';
import { translateToPtBr } from './services/translationService.js';
import { scoreEchoAttempt } from './services/echoScoreService.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Load .env first (for any existing env vars)
dotenv.config({ path: path.join(__dirname, '.env') });

// Load keys from local.properties if not already set (respect .env rule)
// Prefer path relative to this file so cwd does not break key loading.
const localPropsPath = path.resolve(__dirname, '../local.properties');
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

// Alias: some installs store the ElevenLabs key as My-English-Coach-Key only
// (local.properties / Render env). Normalize into ELEVENLABS_API_KEY.
ensureElevenLabsKeyEnv();
if (!process.env.ELEVENLABS_API_KEY && process.env['My-English-Coach-Key']) {
  process.env.ELEVENLABS_API_KEY = process.env['My-English-Coach-Key'];
}

global.WebSocket = ws;

const app = express();
app.use(cors());
app.use(express.json({ limit: '2mb' }));
app.use(programRoutes);

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
    .then(async () => {
      console.log('✅ Conectado ao MongoDB');
      useMongo = true;
      setMongoEnabled(true);
      await loadCurriculumSeed();
    })
    .catch(err => console.error('❌ Erro no MongoDB:', err));
} else {
  console.log('⚠️ MONGODB_URI não configurada. Usando histórico em memória por sessão.');
  setMongoEnabled(false);
  loadCurriculumSeed();
}

/** Load F1 curriculum + B.5 quiz seeds into memory (+ Mongo if enabled). Idempotent. */
async function loadCurriculumSeed() {
  try {
    const { loadCurriculumSeedFile } = await import('./services/loadCurriculumSeed.js');
    const { version, weeks } = loadCurriculumSeedFile();
    const n = await upsertWeeks(weeks);
    console.log(`📚 Curriculum seed v${version ?? '?'} loaded: ${n} weeks`);
  } catch (e) {
    console.error('❌ Failed to load curriculum seed:', e.message);
  }
  try {
    const quizPath = path.join(__dirname, 'seeds', 'elias_quiz_seed.json');
    if (fs.existsSync(quizPath)) {
      const quizSeed = JSON.parse(fs.readFileSync(quizPath, 'utf8'));
      const qn = await upsertQuizzes(quizSeed);
      console.log(
        `📝 Quiz seed v${quizSeed.version ?? '?'} loaded: ${qn} weeks · pass=${quizSeed.passing_score_percent ?? 70}%`
      );
    } else {
      console.warn('⚠️ Quiz seed not found:', quizPath);
    }
  } catch (e) {
    console.error('❌ Failed to load quiz seed:', e.message);
  }
}

// Schemas
const MensagemSchema = new mongoose.Schema({
  role: { type: String, enum: ['user', 'assistant', 'system'], required: true },
  content: { type: String, required: true },
  timestamp: { type: Date, default: Date.now }
});

const ConversaSchema = new mongoose.Schema({
  userId: { type: String, required: true, unique: true },
  mensagens: [MensagemSchema],
  /** ElevenLabs voice locked for this conversation (session continuity §7.2). */
  voiceId: { type: String, default: null },
});
const Conversa = mongoose.model('Conversa', ConversaSchema);

/** Default system prompt (non-program chat) — content unchanged for regression safety. */
const SYSTEM_PROMPT = {
  role: 'system',
  content: DEFAULT_ELIAS_SYSTEM_PROMPT,
};

const turnEngines = new Map();

// Handle WebSocket connections from Android App
io.on('connection', (socket) => {
  console.log('📱 Dispositivo conectado:', socket.id);

  let userIdAtual = socket.id; // Fallback to socket ID if no auth
  let estadoGeracao = { ativo: false, elevenSocket: null, textoParcialIA: "" };
  /** Active system prompt for this socket — default or program-mode. */
  let activeSystemPrompt = { ...SYSTEM_PROMPT };
  let programSession = { active: false, week: null, sessionType: null };
  let historicoMemoria = [activeSystemPrompt];
  /**
   * Session-locked ElevenLabs voiceId (§7.1).
   * Set once on iniciar_sessao / restore; never re-read env mid-conversation.
   */
  let sessionVoiceId = null;

  const engine = new TurnTakingEngine(socket.id);
  turnEngines.set(socket.id, engine);
  registerBargeInHandler(socket);

  function applySystemPromptToHistory() {
    if (historicoMemoria.length === 0 || historicoMemoria[0].role !== 'system') {
      historicoMemoria.unshift(activeSystemPrompt);
    } else {
      historicoMemoria[0] = activeSystemPrompt;
    }
  }

  /** Lock voice for this socket session; persist on Conversa when Mongo is on. */
  async function lockSessionVoice(preferredFromDb = null) {
    if (sessionVoiceId) return sessionVoiceId;
    sessionVoiceId = preferredFromDb || resolveMainChatVoiceId();
    console.log(`🔊 Session voice locked: ${sessionVoiceId} (model=${STREAM_MODEL_ID})`);
    if (useMongo && userIdAtual) {
      try {
        await Conversa.updateOne(
          { userId: userIdAtual },
          { $set: { voiceId: sessionVoiceId } },
          { upsert: false }
        );
      } catch (e) {
        /* non-fatal */
      }
    }
    return sessionVoiceId;
  }

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

  // 1. Authenticate user and load history (default Elias chat — unchanged)
  socket.on('iniciar_sessao', async (userIdOrPayload) => {
    // Accept string userId (legacy) OR { userId, week, sessionType } for program mode
    let userId = userIdOrPayload;
    let week = null;
    let sessionType = null;
    if (userIdOrPayload && typeof userIdOrPayload === 'object') {
      userId = userIdOrPayload.userId;
      week = userIdOrPayload.week ?? null;
      sessionType = userIdOrPayload.sessionType ?? null;
    }

    userIdAtual = userId || socket.id;
    console.log(`👤 Usuário ${userIdAtual} iniciou sessão.`, week ? `(programa week=${week})` : '');

    if (week != null) {
      // F3 — Modo Programa: dynamic system prompt
      const weekDoc = await getWeek(Number(week));
      if (weekDoc) {
        const phase = weekDoc.phase || phaseForWeek(Number(week));
        const progState = await getProgramState();
        activeSystemPrompt = buildSystemPrompt({
          weekDoc,
          phase,
          programMode: true,
          startDate: progState?.start_date || null,
        });
        programSession = { active: true, week: Number(week), sessionType: sessionType || 'themed' };
        historicoMemoria = [activeSystemPrompt];
        await lockSessionVoice(null);
        socket.emit('programa_sessao_pronta', {
          week: Number(week),
          phase,
          sessionType: programSession.sessionType,
          voiceId: sessionVoiceId,
        });
        return;
      }
      console.warn(`⚠️ Semana ${week} não encontrada — fallback prompt padrão`);
    }

    // Default non-program flow
    activeSystemPrompt = { ...SYSTEM_PROMPT };
    programSession = { active: false, week: null, sessionType: null };

    if (useMongo) {
      let conversa = await Conversa.findOne({ userId: userIdAtual });
      if (!conversa) {
        conversa = new Conversa({
          userId: userIdAtual,
          mensagens: [SYSTEM_PROMPT],
          voiceId: resolveMainChatVoiceId(),
        });
        await conversa.save();
      }
      historicoMemoria = conversa.mensagens;
      applySystemPromptToHistory();
      // §7.2 — reuse stored voiceId; legacy docs without field → new default
      await lockSessionVoice(conversa.voiceId || null);
      if (!conversa.voiceId) {
        conversa.voiceId = sessionVoiceId;
        await conversa.save().catch(() => {});
      }
      socket.emit('historico_carregado', conversa.mensagens);
    } else {
      historicoMemoria = [activeSystemPrompt];
      await lockSessionVoice(null);
    }
  });

  // Explicit program session start (F3)
  socket.on('iniciar_sessao_programa', async (payload = {}) => {
    const userId = payload.userId || userIdAtual;
    const week = payload.week;
    const sessionType = payload.sessionType || 'themed';
    userIdAtual = userId || socket.id;
    const weekDoc = await getWeek(Number(week));
    if (!weekDoc) {
      socket.emit('erro_backend', `Week ${week} not found`);
      return;
    }
    const phase = weekDoc.phase || phaseForWeek(Number(week));
    const progState = await getProgramState();
    activeSystemPrompt = buildSystemPrompt({
      weekDoc,
      phase,
      programMode: true,
      startDate: progState?.start_date || null,
    });
    programSession = { active: true, week: Number(week), sessionType };
    historicoMemoria = [activeSystemPrompt];
    await lockSessionVoice(null);
    console.log(
      `📚 Programa sessão: week=${week} phase=${phase} type=${sessionType} start=${progState?.start_date || '?'}`
    );
    socket.emit('programa_sessao_pronta', {
      week: Number(week),
      phase,
      sessionType,
      voiceId: sessionVoiceId,
      startDate: progState?.start_date || null,
    });
  });

  // 1.1 Restore session after reconnect
  socket.on('restore_session', async (payload) => {
    console.log(`🔄 Tentativa de restaurar sessão: ${payload.sessionId}`);
    // Keep existing sessionVoiceId if already locked; else load from DB or default (§7.2)
    if (!sessionVoiceId) {
      let storedVoice = null;
      if (useMongo && userIdAtual) {
        try {
          const conv = await Conversa.findOne({ userId: userIdAtual }).lean();
          storedVoice = conv?.voiceId || null;
        } catch (_) {}
      }
      await lockSessionVoice(storedVoice);
    }
    if (payload.isRestore && payload.historySnapshot) {
      try {
        const snapshot = JSON.parse(payload.historySnapshot);
        if (Array.isArray(snapshot)) {
          historicoMemoria = snapshot.map(m => ({
            role: m.isUser ? 'user' : 'assistant',
            content: m.message
          }));
          if (historicoMemoria.length === 0 || historicoMemoria[0].role !== 'system') {
            historicoMemoria.unshift(activeSystemPrompt);
          } else {
            historicoMemoria[0] = activeSystemPrompt;
          }
          console.log(`✅ Sessão restaurada com ${historicoMemoria.length} mensagens.`);
          socket.emit('session_restored', payload.sessionId);
        }
      } catch (e) {
        console.error("Erro ao restaurar sessão:", e);
      }
    }
  });

  // Expose transcript for F8 when client ends a practice session
  socket.on('get_session_transcript', (ack) => {
    const lines = historicoMemoria
      .filter((m) => m.role === 'user' || m.role === 'assistant')
      .map((m) => `${m.role === 'user' ? 'Student' : 'Tutor'}: ${m.content}`)
      .join('\n');
    if (typeof ack === 'function') ack({ transcript: lines });
    else socket.emit('session_transcript', { transcript: lines });
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

  // 3b. Contextual translation (A.3) — discrete PT under Elias message; never replaces EN
  socket.on('traduzir_texto', async (payload = {}) => {
    const requestId =
      typeof payload === 'object' && payload ? payload.requestId || null : null;
    try {
      const text =
        typeof payload === 'string' ? payload : payload.text || payload.texto || '';
      if (!text.trim()) {
        socket.emit('traducao_pronta', { ok: false, error: 'empty', requestId });
        return;
      }
      const translation = await translateToPtBr(text);
      if (!translation) {
        socket.emit('traducao_pronta', {
          ok: false,
          error: 'empty_translation',
          requestId,
          text,
        });
        return;
      }
      socket.emit('traducao_pronta', {
        ok: true,
        text,
        translation,
        requestId,
      });
    } catch (e) {
      console.error('[traduzir_texto]', e.message);
      socket.emit('traducao_pronta', {
        ok: false,
        error: e.message || 'translation_failed',
        requestId,
      });
    }
  });

  // 3c. Echo Mode scoring (ASR Whisper when available + LLM / heuristic)
  socket.on('echo_avaliar', async (payload = {}) => {
    try {
      const reference = payload.reference || payload.phrase || '';
      const result = await scoreEchoAttempt({
        reference,
        audioBase64: payload.audioBase64 || payload.audio || '',
        mimeType: payload.mimeType || 'audio/mp4',
        durationMs: payload.durationMs || 0,
        focus: payload.focus || '',
        transcript: payload.transcript || '',
      });
      socket.emit('echo_score_pronto', {
        ...result,
        requestId: payload.requestId || null,
      });
    } catch (e) {
      console.error('[echo_avaliar]', e.message);
      socket.emit('echo_score_pronto', {
        ok: false,
        error: e.message,
        score: 0,
        feedback: '',
        transcript: '',
        method: 'error',
        requestId: payload?.requestId || null,
      });
    }
  });

  // 4. Shadowing / Fallback TTS via ElevenLabs (same voice config as main chat)
  socket.on('shadow_speak', async (texto) => {
    try {
      console.log(`🎙️ Shadow speak requisitado: ${String(texto || '').slice(0, 80)}`);
      if (!sessionVoiceId) await lockSessionVoice(null);
      const tts = await openTtsWebSocketWithFallback(sessionVoiceId);
      const pseudoEstado = { ativo: true };
      const seqTracker = { val: 0 };

      // No key at all → client may use local ElevenLabs fallback
      if (!hasElevenLabsKey() || (tts.textOnly && !tts.restOk)) {
        socket.emit('tts_unavailable', {
          reason: tts.error || 'elevenlabs_api_key_missing',
          mode: 'text_only',
          clientFallback: true,
        });
        return;
      }

      // Stream-input failed but REST is available
      if (!tts.socket && tts.restOk) {
        try {
          await emitRestTtsAsOpus(texto, socket, pseudoEstado, seqTracker, {
            voiceId: tts.voiceId || sessionVoiceId,
          });
          return;
        } catch (restErr) {
          console.error('[shadow_speak] REST fallback failed:', restErr.message);
          socket.emit('tts_unavailable', {
            reason: restErr.message || 'voice_open_failed',
            mode: 'text_only',
            clientFallback: true,
          });
          return;
        }
      }

      let elevenSocket = tts.socket;
      let activeVoice = tts.voiceId || sessionVoiceId;
      let usedFallback = false;
      let firstByteWatchdog = null;

      const attachShadowListener = (ws, voiceLabel) => {
        escutarRetornoElevenLabs(ws, socket, pseudoEstado, seqTracker, {
          onAudioMessage: (msg) => firstByteWatchdog?.noteMessage(msg),
        });
        firstByteWatchdog?.cancel();
        firstByteWatchdog = createFirstAudioWatchdog({
          timeoutMs: FIRST_AUDIO_BYTE_TIMEOUT_MS,
          label: `shadow:${voiceLabel || 'voice'}`,
          onTimeout: async () => {
            if (!pseudoEstado.ativo) return;
            console.warn(`[shadow_speak] first-audio-byte timeout voice=${voiceLabel}`);
            try { ws.close(); } catch (_) {}
            const fb = resolveFallbackVoiceId();
            if (!usedFallback && fb && fb !== voiceLabel) {
              usedFallback = true;
              try {
                const fbWs = await openTtsWebSocket(fb, { timeoutMs: 5000 });
                elevenSocket = fbWs;
                activeVoice = fb;
                attachShadowListener(fbWs, fb);
                if (fbWs.readyState === WebSocket.OPEN) {
                  firstByteWatchdog.arm();
                  fbWs.send(JSON.stringify({ text: texto, try_trigger_generation: true }));
                  fbWs.send(JSON.stringify({ text: '' }));
                }
                return;
              } catch (e) {
                console.warn(`[shadow_speak] fallback failed: ${e.message}`);
              }
            }
            // Last resort: REST complete generation
            try {
              await emitRestTtsAsOpus(texto, socket, pseudoEstado, seqTracker, {
                voiceId: activeVoice || sessionVoiceId,
              });
              return;
            } catch (restErr) {
              console.warn(`[shadow_speak] REST after timeout failed: ${restErr.message}`);
            }
            pseudoEstado.ativo = false;
            socket.emit('tts_unavailable', {
              reason: 'first_audio_byte_timeout',
              mode: 'text_only',
              timeoutMs: FIRST_AUDIO_BYTE_TIMEOUT_MS,
              clientFallback: true,
            });
          },
        });
      };

      attachShadowListener(elevenSocket, activeVoice);

      if (elevenSocket.readyState === WebSocket.OPEN) {
        firstByteWatchdog.arm();
        elevenSocket.send(JSON.stringify({ "text": texto, "try_trigger_generation": true }));
        elevenSocket.send(JSON.stringify({ "text": "" }));
      }
    } catch (e) {
      console.error("Erro no shadow_speak:", e);
      socket.emit('tts_unavailable', {
        reason: e.message,
        mode: 'text_only',
        clientFallback: true,
      });
    }
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

    /** Cancelled in finally — must be outer-scoped for catch/finally. */
    let firstByteWatchdog = null;

    try {
      // Registrar os AbortControllers para Barge-in (TurnTaking engine cancel)
      const llmAbort = new AbortController();
      const ttsAbort = new AbortController();
      registerGeneration(socket.id, llmAbort, ttsAbort);

      // Connect to ElevenLabs WebSocket (session-locked voice + fallback §8)
      if (!sessionVoiceId) await lockSessionVoice(null);
      const tts = await openTtsWebSocketWithFallback(sessionVoiceId);
      let elevenSocket = tts.socket;
      /** Pure text-only: no key / REST also impossible. */
      let textOnlyMode = Boolean(tts.textOnly && !tts.restOk);
      /** Prefer complete REST generation after LLM (WS open failed but key ok). */
      let restFallbackMode = Boolean(!tts.socket && tts.restOk && !textOnlyMode);
      let activeTtsVoice = tts.voiceId || sessionVoiceId;
      /** Text already sent to TTS (for re-send on first-byte timeout fallback). */
      let ttsTextSent = '';
      let usedFirstByteFallback = false;
      let audioEmitted = false;
      /**
       * Stream generation token: when primary times out and we switch to fallback,
       * late audio from the abandoned WebSocket must NOT be forwarded (no overlap).
       */
      let ttsStreamGen = 0;
      const abandonTtsSocket = (ws) => {
        ttsStreamGen += 1; // invalidate any in-flight handlers on old socket
        try {
          ws?.removeAllListeners?.('message');
          ws?.removeAllListeners?.('close');
          ws?.removeAllListeners?.('error');
        } catch (_) {}
        try {
          if (ws && ws.readyState === WebSocket.OPEN) ws.close();
        } catch (_) {}
        if (estadoGeracao.elevenSocket === ws) estadoGeracao.elevenSocket = null;
        if (elevenSocket === ws) elevenSocket = null;
      };

      const goTextOnly = (reason, { clientFallback = true } = {}) => {
        textOnlyMode = true;
        restFallbackMode = false;
        ttsStreamGen += 1;
        elevenSocket = null;
        estadoGeracao.elevenSocket = null;
        firstByteWatchdog?.cancel();
        console.warn(`📝 TTS unavailable — text-only mode. reason=${reason}`);
        socket.emit('tts_unavailable', {
          reason: reason || 'voice_open_failed',
          mode: 'text_only',
          timeoutMs: FIRST_AUDIO_BYTE_TIMEOUT_MS,
          clientFallback,
        });
      };

      const attachChatTts = (ws, voiceLabel) => {
        const myGen = ++ttsStreamGen;
        elevenSocket = ws;
        estadoGeracao.elevenSocket = ws;
        activeTtsVoice = voiceLabel;
        restFallbackMode = false;
        escutarRetornoElevenLabs(ws, socket, estadoGeracao, seqTracker, {
          onAudioMessage: (msg) => {
            firstByteWatchdog?.noteMessage(msg);
            if (msg?.audio) audioEmitted = true;
          },
          // Discard late primary audio after fallback/text-only switch
          isActive: () =>
            estadoGeracao.ativo &&
            !textOnlyMode &&
            myGen === ttsStreamGen &&
            estadoGeracao.elevenSocket === ws,
        });
        firstByteWatchdog?.cancel();
        firstByteWatchdog = createFirstAudioWatchdog({
          timeoutMs: FIRST_AUDIO_BYTE_TIMEOUT_MS,
          label: `chat:${voiceLabel || 'voice'}`,
          onTimeout: async () => {
            if (!estadoGeracao.ativo || textOnlyMode) return;
            if (myGen !== ttsStreamGen) return; // already abandoned
            console.warn(
              `[tts] first-audio-byte timeout after ${FIRST_AUDIO_BYTE_TIMEOUT_MS}ms voice=${voiceLabel}`
            );
            // Invalidate stream BEFORE close so any late frames are dropped
            abandonTtsSocket(ws);

            const fb = resolveFallbackVoiceId();
            if (!usedFirstByteFallback && fb && fb !== voiceLabel) {
              usedFirstByteFallback = true;
              try {
                console.warn(`[tts] first-byte fallback → voice=${fb}`);
                // Progressive status for future UI polish (client may ignore)
                socket.emit('tts_status', {
                  phase: 'fallback_voice',
                  message: 'Tentando voz alternativa…',
                  voiceId: fb,
                });
                const fbWs = await openTtsWebSocket(fb, { timeoutMs: 5000 });
                sessionVoiceId = fb;
                attachChatTts(fbWs, fb);
                if (ttsTextSent && fbWs.readyState === WebSocket.OPEN) {
                  firstByteWatchdog.arm();
                  fbWs.send(
                    JSON.stringify({ text: ttsTextSent, try_trigger_generation: true })
                  );
                }
                return;
              } catch (e) {
                console.warn(`[tts] first-byte fallback open failed: ${e.message}`);
              }
            }
            // Prefer REST complete TTS over pure text-only when key is present
            if (hasElevenLabsKey() && ttsTextSent) {
              restFallbackMode = true;
              socket.emit('tts_status', {
                phase: 'rest_fallback',
                message: 'Gerando áudio completo…',
              });
              return;
            }
            goTextOnly('first_audio_byte_timeout');
          },
        });
      };

      if (textOnlyMode) {
        goTextOnly(tts.error || 'elevenlabs_api_key_missing');
      } else if (restFallbackMode) {
        console.warn(
          `[tts] stream-input unavailable — REST complete TTS after LLM. reason=${tts.error || 'ws_failed'}`
        );
        socket.emit('tts_status', {
          phase: 'rest_fallback',
          message: 'Usando TTS completo…',
        });
      } else {
        attachChatTts(elevenSocket, activeTtsVoice);
      }

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
          // Always accumulate for REST complete TTS / first-byte re-send
          ttsTextSent += newResponseText;
          if (elevenSocket && elevenSocket.readyState === WebSocket.OPEN) {
            // Arm first-audio-byte watchdog on the first TTS text flush (D8)
            if (ttsTextSent.length === newResponseText.length) {
              firstByteWatchdog?.arm();
            }
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
              model: 'claude-sonnet-4-6',
              max_tokens: 250,
              system: activeSystemPrompt.content,
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
              systemInstruction: activeSystemPrompt.content
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
              { role: 'system', content: activeSystemPrompt.content },
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
              { role: 'system', content: activeSystemPrompt.content },
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

      if (elevenSocket && elevenSocket.readyState === WebSocket.OPEN) {
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

        // REST complete TTS when stream failed / timed out (still has API key)
        const speakText =
          (parsed?.message || ttsTextSent || '').trim() ||
          getNewResponseText(respostaCompletaIA, 0).text;
        const needRest =
          restFallbackMode ||
          (!audioEmitted &&
            !textOnlyMode &&
            hasElevenLabsKey() &&
            speakText.length > 0 &&
            !(firstByteWatchdog?.hasAudio));
        if (needRest && speakText) {
          try {
            console.log(`[tts] REST complete generation (${speakText.length} chars)`);
            await emitRestTtsAsOpus(speakText, socket, estadoGeracao, seqTracker, {
              voiceId: activeTtsVoice || sessionVoiceId,
            });
            audioEmitted = true;
          } catch (restErr) {
            console.error('[tts] REST complete failed:', restErr.message);
            if (!audioEmitted) {
              goTextOnly(restErr.message || 'rest_tts_failed');
            }
          }
        }
      }

      // If TTS never produced audio but we got text, ensure client leaves loading state
      if (textOnlyMode || (firstByteWatchdog && !firstByteWatchdog.hasAudio && ttsTextSent.length > 0)) {
        // text already streamed via texto_chunk / mensagem_ia — client must not wait forever
        firstByteWatchdog?.cancel();
      }

    } catch (error) {
      console.error("❌ Erro no fluxo principal:", error);
      firstByteWatchdog?.cancel();
      socket.emit("erro_backend", error.message);
    } finally {
      firstByteWatchdog?.cancel();
      clearGeneration(socket.id);
    }
  }

  socket.on('disconnect', () => {
    console.log('❌ Dispositivo desconectado:', socket.id);
  });
});

/**
 * Complete REST TTS → Opus frames (same client event as stream-input).
 * Used when WebSocket stream-input fails but the API key is valid.
 *
 * @param {string} text
 * @param {import('socket.io').Socket} socket
 * @param {{ ativo: boolean }} estadoGeracao
 * @param {{ val: number }} seqTracker
 * @param {{ voiceId?: string }} [opts]
 */
async function emitRestTtsAsOpus(text, socket, estadoGeracao, seqTracker, opts = {}) {
  const trimmed = String(text || '').trim();
  if (!trimmed) throw new Error('empty_tts_text');
  if (estadoGeracao && estadoGeracao.ativo === false) {
    throw new Error('generation_inactive');
  }

  socket.emit('tts_status', {
    phase: 'rest_tts',
    message: 'Gerando voz…',
  });
  socket.emit('estado_ia', 'falando');

  const { pcm, sampleRate, voiceId } = await synthesizePcmRest(
    trimmed,
    opts.voiceId || resolveMainChatVoiceId()
  );
  const opusEncoder = createPcmInt16OpusEncoder({ inputSampleRate: sampleRate });
  const frames = opusEncoder.encode(pcm).concat(opusEncoder.flush());

  for (const frame of frames) {
    if (estadoGeracao && estadoGeracao.ativo === false) break;
    socket.emit('audio_opus_frame', {
      frame: frame.toString('base64'),
      seq: seqTracker.val++,
      ts: Date.now(),
    });
  }

  socket.emit('estado_ia', 'ociosa');
  console.log(
    `[tts] REST→Opus done voice=${voiceId} frames=${frames.length} pcmBytes=${pcm.length}`
  );
}

/**
 * Stream ElevenLabs audio to Android as Opus frames.
 * ElevenLabs stream-input must use output_format=pcm_* (Int16 LE).
 * Encoding as Float32/MP3 was the root cause of static/hiss (chiado).
 *
 * @param {object} [hooks]
 * @param {(msg: object) => void} [hooks.onAudioMessage] — first-audio-byte watchdog
 * @param {() => boolean} [hooks.isActive] — false after stream abandoned (late audio discard)
 */
function escutarRetornoElevenLabs(elevenSocket, socket, estadoGeracao, seqTracker, hooks = {}) {
  socket.emit("estado_ia", "falando");

  const inputSampleRate = sampleRateFromOutputFormat(STREAM_OUTPUT_FORMAT);
  const opusEncoder = createPcmInt16OpusEncoder({ inputSampleRate });
  const stillActive = () =>
    estadoGeracao.ativo && (typeof hooks.isActive !== 'function' || hooks.isActive());

  const emitOpusFrames = (opusFrames) => {
    if (!stillActive()) return; // hard discard after fallback switch
    opusFrames.forEach((frame) => {
      socket.emit("audio_opus_frame", {
        frame: frame.toString("base64"),
        seq: seqTracker.val++,
        ts: Date.now(),
      });
    });
  };

  elevenSocket.on("message", (msgStr) => {
    if (!stillActive()) return;
    try {
      const raw = typeof msgStr === 'string' ? msgStr : msgStr.toString();
      const msg = JSON.parse(raw);
      if (msg.audio) {
        if (!stillActive()) return;
        // Notify first-audio-byte watchdog (D8) before encode
        try {
          hooks.onAudioMessage?.(msg);
        } catch (_) {
          /* ignore */
        }
        // pcm_* formats: base64 of raw s16le mono (NOT mp3, NOT float32)
        const pcmInt16 = Buffer.from(msg.audio, 'base64');
        emitOpusFrames(opusEncoder.encode(pcmInt16));
      }
      if (msg.isFinal) {
        if (!stillActive()) return;
        emitOpusFrames(opusEncoder.flush());
        socket.emit("estado_ia", "ociosa");
      }
    } catch (e) {
      console.error("Erro processando retorno ElevenLabs:", e);
    }
  });

  // Ensure last partial frame is not dropped if socket closes without isFinal
  elevenSocket.on("close", () => {
    if (!stillActive()) return;
    try {
      emitOpusFrames(opusEncoder.flush());
    } catch (_) {
      /* ignore */
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

/** Lightweight health — no secrets. Used to verify deploy + TTS env. */
app.get('/health', (req, res) => {
  ensureElevenLabsKeyEnv();
  let opusBackend = 'unknown';
  try {
    opusBackend = getOpusBackend();
  } catch (e) {
    opusBackend = `error:${e.message}`;
  }
  res.json({
    ok: true,
    elevenLabsKey: hasElevenLabsKey(),
    elevenLabsKeySource: resolveApiKeySource(),
    mainChatVoiceId: resolveMainChatVoiceId(),
    fallbackVoiceId: resolveFallbackVoiceId(),
    streamOutputFormat: STREAM_OUTPUT_FORMAT,
    streamModel: STREAM_MODEL_ID,
    opusBackend,
    mongo: useMongo,
    hint: hasElevenLabsKey()
      ? undefined
      : 'Set ELEVENLABS_API_KEY (or My-English-Coach-Key) on the host env (e.g. Render).',
  });
});

server.listen(PORT, () => {
  ensureElevenLabsKeyEnv();
  let opusBackend = 'unresolved';
  try {
    opusBackend = getOpusBackend();
  } catch (e) {
    opusBackend = `error:${e.message}`;
    console.error('[boot] Opus encoder failed to load:', e.message);
  }
  console.log(`🚀 Servidor rodando na porta ${PORT}`);
  console.log(
    `[boot] TTS main=${resolveMainChatVoiceId()} format=${STREAM_OUTPUT_FORMAT} model=${STREAM_MODEL_ID} elevenLabsKey=${hasElevenLabsKey()} source=${resolveApiKeySource()} opus=${opusBackend}`
  );
  if (!hasElevenLabsKey()) {
    console.error(
      '[boot] ⚠️ ELEVENLABS_API_KEY missing — set it (or My-English-Coach-Key) on Render Environment. TTS will stay silent until fixed.'
    );
  }
});
