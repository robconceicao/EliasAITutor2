# V7 — Auditoria e aposentadoria da voz Adam

**Data:** 2026-07-10  
**Spec:** Mini-Spec Adam retirement  
**Status:** Implementado

---

## Achados da verificação (§4)

### Backend

| Local | Tipo | Ação |
|---|---|---|
| `server.js` ~L301 `shadow_speak` | **Hardcode** `pNInz6obpgDQGcFmaJcg` // Adam | → `openTtsWebSocketWithFallback(sessionVoiceId)` |
| `server.js` ~L351 `handleAIResponse` | **Hardcode** Adam | → idem |
| `services/elevenLabsClient.js` | Comentário + chunks Liam | Expandido: MAIN_CHAT / FALLBACK / CHUNK |
| `seeds/*.json` | "adam" em texto PT de chunks | **Falso positivo** — não é voiceId |

### Android

| Local | Tipo | Ação |
|---|---|---|
| `GameConstants.kt` `VOICE_AMERICAN` | Hardcode `pNInz6obpgDQGcFmaJgB` (label Adam; id **ligeiramente diferente** do backend) | → Liam `TX3LPaxmHKxFdv7VOQHJ` |
| `GameConstants.kt` `VOICE_BRITISH` | Hardcode Antoni (legado) | → Chris `iP95p4xoKVk53GoZ742B` |
| `ElevenLabsApi.kt` | API genérica; `voiceId` por path, **sem default Adam** | + `BuildConfig.ELEVENLABS_VOICE_ID` (default Liam) |
| Uso em runtime | Nenhum call site a `VOICE_AMERICAN` / `textToSpeech` no fluxo principal | Chat TTS = **só backend** |

### Docs (não-runtime)

- `CLAUDE.md`, `docs/FASE0_…`, `repomix-output.xml` — documentação / snapshot; não afetam o app.

### Conclusão V7

- **Único hardcode runtime real de Adam no backend:** 2 linhas em `server.js`.
- **Android:** constantes legadas não usadas no path principal; atualizadas por higiene.
- **Modelo de streaming:** permanece `eleven_flash_v2_5`.

---

## Configuração (§6)

| Env | Default no código | Papel |
|---|---|---|
| `MAIN_CHAT_VOICE_ID` | Liam `TX3LPaxmHKxFdv7VOQHJ` | Chat + shadow_speak |
| `FALLBACK_VOICE_ID` | Chris `iP95p4xoKVk53GoZ742B` | Se a primária falhar ao abrir WS |
| `CHUNK_VOICE_ID` | Liam (independente) | Drill de chunks |

`MAIN_CHAT_VOICE_ID` e `CHUNK_VOICE_ID` são **independentes**.  
Se `MAIN_CHAT_VOICE_ID=Adam`, o código **ignora** e usa Liam (nunca regride para legado).

---

## Continuidade e fallback

- Voice **travada por socket** em `iniciar_sessao` / programa / restore.
- Campo `Conversa.voiceId` (nullable) no Mongo; docs antigos → default novo.
- Fallback: primary → Chris → `tts_unavailable` + modo texto (LLM/chat seguem).

---

## Aceite (checklist)

- [x] Sem `pNInz6obpgDQGcFmaJcg` no código-fonte runtime (server + kt)
- [x] Env ausente → Liam, não Adam
- [x] Session lock + restore legado
- [x] Fallback + text-only
- [x] Modelo `eleven_flash_v2_5` inalterado
- [x] Pipeline de áudio (VAD/jitter/barge-in/Opus/RNNoise/turn-taking) não tocado
