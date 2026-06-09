# CLAUDE.md — Elias AI Tutor

> **Regra obrigatória:** Ler `AGENTS.md` antes de modificar qualquer parte do projeto (`SYSTEM_RULES.md`).
> Para arquitetura detalhada e histórico de melhorias, ver `CLAUDE_IMPROVEMENTS.md`.

## Visão Geral
Tutor de inglês gamificado com IA em tempo real para Android. O usuário conversa por voz; o backend processa com LLMs (Claude/Gemini/DeepSeek) e responde via streaming de áudio (ElevenLabs no fluxo principal; Cartesia nas telas de Immersion e Shadowing). Todo o pipeline de voz — captura, VAD, barge-in, jitter buffer, Opus codec — roda localmente no dispositivo.

## Arquitetura do Sistema

```
[Android App — Kotlin/Compose]
        │  Socket.io (WebSocket)
        ▼
[Backend — Node.js + Socket.io]
        ├──▶ LLM Stream  (Claude → Gemini → DeepSeek, failover automático)
        └──▶ TTS Stream  (ElevenLabs WebSocket → PCM Float32 chunks)
             [Cartesia usado apenas em Immersion/Shadowing]

[Android — Supabase Client nativo]  (perfil, gamificação, histórico)
```

## Stack Técnica

### Android (app/)
| Componente | Tecnologia | Versão |
|---|---|---|
| Linguagem | Kotlin | 2.1.21 |
| UI | Jetpack Compose + Material3 | BOM 2025.05.00 |
| ViewModel | AndroidViewModel + StateFlow | lifecycle 2.8.7 |
| Persistência local | DataStore Preferences | 1.1.1 |
| Build | AGP | 8.7.3 |

### Backend (backend_nodejs/)
| Componente | Tecnologia | Versão |
|---|---|---|
| Servidor | Express + Socket.io | ^4.21.2 / ^4.8.1 |
| Módulo | ES Modules (`"type": "module"`) | — |
| LLM — primário | Anthropic SDK | ^0.33.1 |
| LLM — fallback 1 | Google Generative AI | ^0.24.1 |
| LLM — turn-taking | Groq SDK | ^1.2.1 |
| TTS principal | ElevenLabs WebSocket | eleven_flash_v2_5, voiceId Adam |
| TTS secundário | @cartesia/cartesia-js | ^1.1.2 — Immersion/Shadowing apenas |
| Codec | @discordjs/opus | ^0.10.0 |
| DB | Mongoose (MongoDB, opcional) | ^8.9.3 |
| Chaves | dotenv + local.properties | — |

## Estrutura de Diretórios Real

```
Elias/
├── app/src/main/java/com/roberto/eliasaitutor/
│   ├── audio/
│   │   ├── AudioCaptureManager.kt
│   │   ├── AudioEngine.kt          # SpeechRecognizer + AudioTrack + AcousticEchoCanceler
│   │   ├── AudioHelper.kt
│   │   ├── BargeInController.kt    # Máquina de estados: IDLE/AI_SPEAKING/BARGED_IN/USER_SPEAKING
│   │   ├── JitterBuffer.kt         # Buffer adaptativo anti-jitter
│   │   ├── LocalVAD.kt             # VAD baseado em RMS (IDLE/SPEECH/TRAILING_SILENCE)
│   │   ├── OpusAudioPlayer.kt      # Decoder Opus via MediaCodec + AudioTrack 48kHz
│   │   ├── PLCGenerator.kt         # Packet Loss Concealment
│   │   └── RnnoiseProcessor.kt     # Supressão de ruído neural
│   ├── data/
│   │   ├── DataStoreManager.kt     # Persistência de perfil/progresso
│   │   └── GameConstants.kt
│   ├── model/
│   │   ├── GameModels.kt
│   │   └── UserProfile.kt
│   ├── network/
│   │   ├── AnthropicApi.kt
│   │   ├── CartesiaApi.kt          # WebSocket para TTS streaming
│   │   ├── DeepSeekApi.kt
│   │   ├── ElevenLabsApi.kt
│   │   ├── GroqApi.kt
│   │   ├── OpenAIApi.kt
│   │   ├── SocketClient.kt         # Cliente Socket.io central (singleton)
│   │   └── SupabaseClient.kt
│   ├── ui/
│   │   ├── OndasSonorasAgente.kt   # 7 barras animadas (UI de voz)
│   │   ├── components/
│   │   │   ├── PdfGenerator.kt
│   │   │   ├── RadarChart.kt
│   │   │   └── VoiceWaveformVisualizer.kt
│   │   ├── screens/
│   │   │   ├── ChatScreen.kt
│   │   │   ├── ImmersionScreen.kt
│   │   │   ├── ProgressScreen.kt
│   │   │   ├── ShadowingScreen.kt
│   │   │   └── StoreScreen.kt
│   │   └── theme/
│   ├── viewmodel/
│   │   └── EliasViewModel.kt       # Coordenador central de voz, chat e gamificação
│   └── MainActivity.kt
├── backend_nodejs/
│   ├── server.js                   # Entry point: Socket.io + LLM + TTS + MongoDB
│   ├── audioEncoder.js             # PCM Float32 → Opus (48kHz, mono, 960 frames)
│   ├── bargeInHandler.js           # AbortController para LLM + TTS no barge-in
│   ├── turnTakingEngine.js         # Decisão WAIT/RESPOND/CLARIFY (Groq semântico)
│   ├── package.json                # ES Modules
│   ├── test_all_keys.js
│   ├── test_anthropic.js
│   └── test_client.js
├── gradle/libs.versions.toml
├── AGENTS.md                       # ⚠️ LEITURA OBRIGATÓRIA antes de editar
├── CLAUDE_IMPROVEMENTS.md          # Arquitetura detalhada e histórico
└── SYSTEM_RULES.md
```

## Pipeline de Áudio (Implementado)

### Status dos 7 Upgrades (atualizado 2026-06-08)
| # | Feature | Status | Obs |
|---|---|---|---|
| 1 | Opus codec | ✅ corrigido | `Thread.sleep()` → `coroutine delay()` — commit 36d2ec2 |
| 2 | WebSocket reconnect resiliente | ✅ funcional | — |
| 3 | VAD RMS state machine | ✅ funcional | — |
| 4 | Adaptive jitter buffer | ✅ corrigido | `clear()` agora reseta `playoutStartSystemTime` — commit da8473c |
| 5 | Barge-in atômico | ✅ funcional | — |
| 6 | RNNoise supressão de ruído | ✅ implementado | 2 camadas: hardware DSP + spectral gating — commit d4d8a88 |
| 7 | Turn-taking engine | ✅ funcional | 3 camadas: regras rápidas + heurísticas + Groq semântico |

### Gravação (Android → Backend)
```
Microfone
  → RnnoiseProcessor (2 camadas: NoiseSuppressor hardware + spectral gating software)
  → LocalVAD (RMS threshold: speech 0.015, silence 0.007)
      ├── onSpeechStart() → barge-in se Elias estiver falando
      └── onSpeechEnd(pcmBytes) → SpeechRecognizer (STT local, pt-BR)
              → transcrição → socket.emit('mensagem_usuario')
```

### Reprodução (Backend → Android)
```
Backend: ElevenLabs WebSocket (eleven_flash_v2_5, voiceId: pNInz6obpgDQGcFmaJcg)
  → escutarRetornoElevenLabs() → PCM Float32 chunks
  → audioEncoder.js: PCM Float32 → Opus (48kHz mono, frame 960 = 20ms)
  → socket.emit('audio_chunk', base64)

Android:
  → SocketClient recebe 'audio_chunk'
  → JitterBuffer (sequência + timestamp)
  → OpusAudioPlayer (MediaCodec decoder → PCM Int16)
  → AudioTrack.MODE_STREAM (48kHz, MONO, PCM_16BIT)
  → AcousticEchoCanceler (no audioSessionId do player)
```

### Barge-in (Interrupção)
```
LocalVAD detecta voz durante reprodução
  → BargeInController.onUserBeginsSpeech()
      ├── Debounce: 300ms
      ├── Min AI speech: 400ms (evita falso positivo)
      └── socket.emit('barge_in', { sessionId })

Backend bargeInHandler.js:
  → llmAbort.abort()   (cancela stream do Claude/Gemini)
  → ttsAbort.abort()   (cancela stream ElevenLabs)
  → socket.emit('audio_stream_cancelled')
```

## Eventos Socket.io

### Android → Backend
| Evento | Payload | Descrição |
|---|---|---|
| `mensagem_usuario` | `{ sessionId, texto, userId }` | Transcrição do usuário |
| `barge_in` | `{ sessionId }` | Usuário interrompeu Elias |
| `usuario_interrompeu` | `{ sessionId }` | Alias do barge_in (ViewModel) |

### Backend → Android
| Evento | Payload | Descrição |
|---|---|---|
| `audio_chunk` | Base64 (Opus frames) | Chunks de áudio do TTS |
| `texto_chunk` | `{ delta }` | Streaming de texto do LLM |
| `estado_ia` | `"falando"` \| `"ociosa"` | Estado do Elias |
| `mensagem_ia` | `{ texto, erros, sentimento }` | Resposta final completa |
| `audio_stream_cancelled` | `{ sessionId, reason }` | Barge-in confirmado |

## Turn-Taking Engine (turnTakingEngine.js)
Decide quando responder com base em três camadas:
1. **Regras rápidas:** pontuação final → RESPOND; fim de conjunção → WAIT; 1 palavra → CLARIFY
2. **Heurísticas de delay:** pergunta → delay × 0.7; frase curta → delay × 1.4; range 400ms–2500ms
3. **Semântica via Groq:** verifica se a frase está completa semanticamente

**Groq** é usado apenas para turn-taking (baixa latência, baixo custo) — não para geração de conteúdo.

## Failover Multi-LLM (server.js)
```
Claude (Anthropic) → Gemini 2.5 Flash → DeepSeek Chat
```
Troca automática se chave expirar ou API falhar — sem interromper o usuário.

## Gerenciamento de Chaves (local.properties)
O backend carrega chaves de **dois lugares** com prioridade:
1. `.env` (tem prioridade — nunca sobrescrito)
2. `local.properties` (na raiz do projeto Android, fora de `backend_nodejs/`)

```properties
# local.properties (raiz do projeto)
ANTHROPIC_API_KEY=...
GEMINI_API_KEY=...
DEEPSEEK_API_KEY=...
GROQ_API_KEY=...
ELEVENLABS_API_KEY=...  # TTS principal (fluxo de resposta do Elias)
CARTESIA_API_KEY=...        # TTS secundário (Immersion/Shadowing)
MONGODB_URI=...       # opcional — sem ele, histórico fica em memória por sessão
```

As chaves do Android (Cartesia para Immersion/Shadowing, etc.) ficam em `local.properties` e são injetadas via `BuildConfig`.

## MongoDB — Fallback em Memória
MongoDB é **opcional**. Se `MONGODB_URI` não estiver configurada, o backend usa histórico em memória por sessão (reinicia ao desconectar). Para persistência entre sessões, configurar MongoDB.

## UI de Voz — OndasSonorasAgente.kt
7 barras animadas com gradientes neon reativos ao estado:
- 🔴/🟠 Vermelho/Laranja = Usuário falando
- 🟦/🔵 Teal/Cyan = Elias falando  
- 💙 Azul pulsante = Elias pensando

## Gamificação
- Perfil persistido em `DataStore` via `DataStoreManager`
- Constantes em `GameConstants.kt`
- Modelos em `GameModels.kt`
- Loja em `StoreScreen.kt`
- Progresso em `ProgressScreen.kt` com `RadarChart`

## Comandos Úteis

### Backend
```bash
cd backend_nodejs
npm install
node server.js             # produção
node test_all_keys.js      # verificar todas as chaves configuradas
node test_anthropic.js     # testar Claude
node test_client.js        # testar conexão Socket.io
```

### Android
```bash
# Android Studio → Run
# ou via Gradle:
./gradlew assembleDebug
./gradlew installDebug     # instala no dispositivo conectado
```

## Parâmetros Críticos de Áudio (NÃO alterar sem testes)

| Parâmetro | Valor | Localização |
|---|---|---|
| Sample rate Opus | 48000 Hz | audioEncoder.js + OpusAudioPlayer.kt |
| Frame size Opus | 960 samples = 20ms | audioEncoder.js + OpusAudioPlayer.kt |
| RMS speech threshold | 0.015 | LocalVAD.kt |
| RMS silence threshold | 0.007 | LocalVAD.kt |
| Speech confirm frames | 3 | LocalVAD.kt |
| Silence confirm frames | 25 | LocalVAD.kt |
| Barge-in debounce | 300ms | BargeInController.kt |
| Min AI speech antes de barge-in | 400ms | BargeInController.kt |
| Turn-taking max wait | 2500ms | turnTakingEngine.js |
| Turn-taking min silence | 800ms | turnTakingEngine.js |
| SocketClient heartbeat | 25000ms | SocketClient.kt |
| SocketClient max reconnect | 10 tentativas | SocketClient.kt |

## O que NÃO fazer
- Não alterar os parâmetros de áudio da tabela acima sem rodar testes de barge-in
- Não fazer chamadas a LLMs diretamente do Android — sempre via backend Socket.io
- Não usar `Thread.sleep()` no pipeline de áudio — usar coroutines com `delay()`
- Não acumular mensagens Socket.io sem `sessionId` — causa mistura de contexto
- Não implementar múltiplos upgrades de áudio em um mesmo commit
- Não commitar `local.properties` nem `.env`
- Não remover o fallback em memória do MongoDB — é o comportamento de dev padrão
- Não usar `AGENTS.md` como referência de stack (está desatualizado — menciona Flutter/FastAPI mas o projeto real é Kotlin/Node.js)
