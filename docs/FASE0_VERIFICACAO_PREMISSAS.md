# Fase 0 — Verificação de Premissas (V1–V6)

**Data:** 2026-07-10  
**Repositório:** EliasAITutor2  
**Status:** Concluída — pronta para Fase A

---

## V1 — Banco e mecanismo de migração

| | |
|---|---|
| **Achado** | Backend usa **MongoDB via Mongoose** (`mongoose ^8.9.3`). Não há pasta de migrações, ORM SQL nem Prisma/Sequelize. Conexão opcional: se `MONGODB_URI` ausente, o app usa **histórico em memória** por sessão (`server.js`). Única coleção existente: `Conversa` (userId + mensagens). |
| **Adaptação** | Sem engine de migração formal. Criar **schemas Mongoose novos** (`ProgramWeek`, `UserProgramState`, `PracticeSession`) + seed idempotente (upsert por `week`). Fallback em memória (Map) quando MongoDB indisponível — mesmo padrão do chat. Documentar seed como “migração de dados” equivalente. |
| **Impacto** | Fase A: `models/programModels.js`, `seedProgram.js`, sem editar schemas existentes. |

---

## V2 — Single-user vs multiusuário

| | |
|---|---|
| **Achado** | App é **single-user de fato**. `UserProfile.userId` default `"local_user"`; UUID gerado localmente no primeiro boot (`DataStore`). Sem tela de login. Supabase sincroniza perfil/gamificação por `user_id` local, não auth multi-conta. Backend: `iniciar_sessao` recebe `userId` opcional; fallback `socket.id`. |
| **Adaptação** | Manter `user_program_state` como **registro único** (singleton). Opcionalmente gravar `userId` se enviado, mas não exigir multiusuário. |
| **Impacto** | Fases B–E: sem `user_id` obrigatório nas rotas; estado global single-user. |

---

## V3 — Autenticação / middleware de rotas

| | |
|---|---|
| **Achado** | **Sem middleware de auth** nas rotas HTTP. Express só tem `cors` + `GET /`. API real é **Socket.io sem token**. Android usa `BuildConfig.BACKEND_URL`. |
| **Adaptação** | Rotas REST do programa no **mesmo nível de exposição** (CORS aberto, sem auth). Nunca menos protegidas que o existente. |
| **Impacto** | Fases A–E: rotas `/program/*`, `/sessions/*`, `/progress/*` sem auth layer. |

---

## V4 — Onde o system prompt é montado

| | |
|---|---|
| **Achado** | Ponto único de definição: constante `SYSTEM_PROMPT` em `server.js` (linhas ~79–116). Usada em: init de `historicoMemoria`, `iniciar_sessao` (Mongo), `restore_session`, e em **todos** os LLMs (Claude `system:`, Gemini `systemInstruction`, DeepSeek/Groq `role: system`). |
| **Adaptação** | Extrair `buildSystemPrompt({ week, sessionType })` em módulo; se sessão do Modo Programa, concatenar prompt-mestre da fase + `conversation_prompt` + instrução de voz. Fluxo atual (sem `week`) continua com `SYSTEM_PROMPT` original — **regressão zero**. |
| **Impacto** | Fase B: handshake `iniciar_sessao` / evento novo `iniciar_sessao_programa` com `{ week, sessionType }`; trocar referências a `SYSTEM_PROMPT.content` por prompt dinâmico da sessão. |

---

## V5 — Transcript no backend

| | |
|---|---|
| **Achado** | Transcript **já acumulado no backend** como `historicoMemoria`: array de `{ role, content, timestamp? }` (user/assistant/system). Persistido em Mongo quando disponível. Turn-taking recebe `transcript` pontual em `speech_end`, mas o histórico completo está em `historicoMemoria`. |
| **Adaptação** | F8 reutiliza `historicoMemoria` filtrado (user/assistant) ao encerrar sessão. Campo `transcript` no `PATCH /sessions/:id/end` aceito como fallback se cliente enviar. **Não armazenar áudio.** |
| **Impacto** | Fases C/E: ao end session, gerar feedback a partir do histórico da sessão de programa. |

---

## V6 — Integração ElevenLabs

| | |
|---|---|
| **Achado** | Backend: WebSocket streaming `wss://api.elevenlabs.io/v1/text-to-speech/{voiceId}/stream-input?model_id=eleven_flash_v2_5`, `voiceId = pNInz6obpgDQGcFmaJcg` (**Adam** — voz Default legada). Inline em `server.js` (sem wrapper único). Android: `ElevenLabsApi.kt` REST `v1/text-to-speech/{voiceId}` com `eleven_turbo_v2` (Immersion/Shadowing usam Cartesia). |
| **Adaptação** | Criar `elevenLabsClient.js` (ponto único) antes/durante Fase E. **D4:** não usar Adam/Antoni/Josh (expiram 31/12/2026). Preferir voz masculina clara da library (config `CHUNK_VOICE_ID` ou default documentado). Áudio dos chunks gerado **no seed** e cacheado em disco (`backend_nodejs/cache/chunks/`). |
| **Impacto** | Fase E: pre-generate TTS no seed; app baixa/reproduz cache. Conversação principal mantém Adam por enquanto (fora do escopo de mudança de voz do chat). |

---

## Seed de currículo (anexo)

| | |
|---|---|
| **Achado** | `elias_curriculum_seed.json` **não estava no repositório**. Extraído do documento `Fluencia_Ingles_26_Semanas.docx` (Downloads) via parser `scripts/parse_curriculum.py`. |
| **Resultado** | 26 semanas, 10 chunks/semana, 8 frases Anki, `conversation_prompt` por semana. Arquivo: `backend_nodejs/seeds/elias_curriculum_seed.json`. |

---

## Decisões de produto (defaults D1–D4)

| # | Default a implementar | Fase |
|---|---|---|
| D1 | Timer = tempo de **sessão ativa** (foreground) | C |
| D2 | Sem lembrete extra de streak; só horário fixo | D |
| D3 | Truncar transcript F8 aos ~últimos 8k tokens se necessário | E |
| D4 | Voz chunks: masculina clara, **não** Default legada; cache no seed | E |

---

## Ordem de implementação (confirmada)

0. ~~Fase 0 — Verificação~~ ✅  
1. **Fase A** — seed + GET weeks  
2. **Fase B** — state + home + prompt dinâmico  
3. **Fase C** — sessões + timer  
4. **Fase D** — progresso + lembrete  
5. **Fase E** — chunks + feedback  
