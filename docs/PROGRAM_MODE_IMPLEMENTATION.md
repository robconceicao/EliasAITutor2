# Modo Programa — Implementação (F1–F8)

## Fase 0
Relatório: `docs/FASE0_VERIFICACAO_PREMISSAS.md`

## Backend (`backend_nodejs/`)

| Arquivo | Função |
|---|---|
| `seeds/elias_curriculum_seed.json` | 26 semanas (extraído do livro Fluência) |
| `seedProgram.js` | Seed idempotente (`npm run seed` / `npm run seed:tts`) |
| `models/programModels.js` | Schemas Mongoose novos |
| `services/programStore.js` | Memória + Mongo; state, sessions, progress |
| `services/promptBuilder.js` | F3 system prompt dinâmico |
| `services/sessionFeedback.js` | F8 relatório LLM |
| `services/elevenLabsClient.js` | F7 TTS cache (único ponto ElevenLabs REST) |
| `routes/programRoutes.js` | API §4.5 |
| `server.js` | Wire REST + `iniciar_sessao_programa` + prompt ativo |

### API
- `GET /program/weeks`, `GET /program/weeks/:n`
- `GET|PUT /program/state`
- `POST /sessions`, `PATCH /sessions/:id/end`, `GET /sessions/:id/feedback`
- `GET /progress/summary`
- `GET /program/chunks/audio/:week/:index`

### Decisões (defaults D1–D4)
- **D1** Timer = sessão em foreground (pausa em ON_PAUSE)
- **D2** Só lembrete no horário fixo
- **D3** Transcript truncado a ~32k chars no F8
- **D4** Voz chunks: `CHUNK_VOICE_ID` default `TX3LPaxmHKxFdv7VOQHJ` (Liam) — **não** Adam/Antoni/Josh. Override via env. Pre-gerar: `npm run seed:tts`

## Android (`app/.../program/` + screens)

- Tab **Programa** na bottom bar
- Home, Settings, Chunks drill, Progress, Feedback
- Timer overlay no Chat durante sessão do programa
- AlarmManager + BootReceiver para lembrete

## Testes
```bash
cd backend_nodejs && node server.js   # terminal 1
npm run test:program                  # terminal 2
./gradlew :app:testDebugUnitTest --tests ProgramWeekCalcTest
./gradlew :app:compileDebugKotlin
```

## Não alterado (regressão)
Pipeline de áudio: VAD, jitter, barge-in, Opus, RNNoise, turn-taking.  
Chat fora do Modo Programa usa o `SYSTEM_PROMPT` original.
