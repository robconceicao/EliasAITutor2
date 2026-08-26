# SPEC-0001 — Failover de provedor de TTS quando a chave ElevenLabs falha

> Status: `rascunho`
> Autor: Roberto · Criada em: 2026-08-26 · Última atualização: 2026-08-26
> Branch de trabalho: `feat/0001-tts-provider-failover`
> ADRs relacionadas: `specs/decisions/ADR-0001-tts-provider-failover.md`

---

## 1. Objetivo

Quando a chave da ElevenLabs falhar por autenticação/cota, o Elias continua **falando** —
usando um segundo provedor de voz — em vez de cair para texto puro.

**Problema observado (com evidência):**
- Print do device (2026-08-26, 15:38, Semana 1): toast
  `Voz indisponível (ElevenLabs REST TTS 400: {"detail":{"type":"authentication_err…`.
  A resposta do Elias apareceu escrita, sem áudio nenhum.
- Causa estrutural: toda a cadeia de fallback atual é **do mesmo provedor e da mesma chave**.
  Em `backend_nodejs/services/elevenLabsClient.js` a cadeia é
  `openTtsWebSocketWithFallback()` (voz principal → voz reserva, linhas 279-333)
  → `synthesizePcmRest()` (REST completo, linha 346) → texto puro.
  Se a **chave** está inválida, os três degraus falham juntos.
- O fallback local do Android (`network/ElevenLabsApi.kt`, acionado por
  `ttsUnavailableFlow` em `viewmodel/EliasViewModel.kt:680`) usa **a mesma ElevenLabs**,
  então também falha — e ainda gasta ~2 s de espera antes do toast final.

**Como saberemos que resolveu:**
- Com `ELEVENLABS_API_KEY` propositalmente inválida no ambiente do backend, o usuário
  ouve a resposta do Elias normalmente (voz do provedor secundário) e vê no máximo um
  aviso discreto de "voz reserva", nunca uma resposta muda.

---

## 2. Escopo

- [ ] Novo módulo `backend_nodejs/services/ttsProvider.js`: escolhe o provedor,
      classifica o erro e mantém cooldown do provedor derrubado.
- [ ] Novo cliente `backend_nodejs/services/cartesiaClient.js`: síntese completa
      (não-streaming) → PCM Int16 LE, mesma interface de retorno de `synthesizePcmRest()`.
- [ ] `backend_nodejs/server.js`: nos dois pontos que hoje emitem `tts_unavailable`
      (chat principal, ~linha 1177, e `shadow_speak`, ~linhas 583-673), tentar o provedor
      secundário **antes** de degradar para texto.
- [ ] Taxonomia de `reason` em `tts_unavailable` (seção 5.2).
- [ ] Rota `GET /health/tts` com o estado de cada provedor (sem segredos).
- [ ] `app/.../viewmodel/EliasViewModel.kt`: quando `reason` for `all_providers_failed`
      ou `*_auth_failed`, **não** tentar o fallback local de ElevenLabs (economiza 2 s).
- [ ] Teste `backend_nodejs/test_tts_failover.js` + entrada em `npm run test:unit`.

### 2.1 Não-escopo (explícito)

- Não mexer no pipeline Opus: `audioEncoder.js`, `OpusAudioPlayer.kt`, `JitterBuffer.kt`.
  Sample rate 48 kHz e frame 960 permanecem intocados (CLAUDE.md, "Parâmetros Críticos").
- Não mexer em barge-in (`bargeInHandler.js`, `BargeInController.kt`) nem em VAD.
- Não trocar a ElevenLabs de lugar: ela continua sendo o provedor **primário**.
- Não implementar streaming no Cartesia nesta spec — só síntese completa. Streaming é a SPEC-0002.
- Não mexer em `services/llmClient.js` nem no failover de LLM.
- Não tocar em Modo Programa, `programStore.js`, `placementService.js`, migrations ou seeds.
- Não adicionar SDK novo se `fetch` nativo resolver (ver D3).

---

## 3. Decisões de arquitetura (com justificativa)

| # | Decisão | Alternativa descartada | Por que |
|---|---|---|---|
| D1 | Failover é **por provedor**, não por voz. `ttsProvider.js` conhece a ordem `elevenlabs → cartesia → texto`. | Continuar empilhando vozes dentro de `elevenLabsClient.js` | Voz reserva não resolve chave morta — foi exatamente o caso do print. |
| D2 | Cartesia entra como **síntese completa** (REST), não streaming. | Implementar WebSocket stream-input do Cartesia já | O caminho REST→Opus já existe e é testado (`emitRestTtsAsOpus` em `server.js:1205`). Reaproveitar corta a entrega pela metade e a latência do fallback é aceitável (é degradação, não caminho feliz). |
| D3 | Chamar a API do Cartesia com `fetch` nativo do Node 20, sem SDK. | `@cartesia/cartesia-js` | Uma requisição HTTP só; SDK adiciona dependência, superfície de update e peso no cold start do Render free. |
| D4 | Cooldown de 10 min por provedor após erro de auth/cota (espelha `markClaudeUnavailable()` em `llmClient.js:41`). | Tentar ElevenLabs a cada turno | Evita 1-3 s de latência por turno enquanto a chave estiver morta, e o padrão já existe no projeto — o time (você) já sabe ler. |
| D5 | O backend converte o áudio do Cartesia para o **mesmo formato** que já entra no encoder: PCM Int16 LE, e informa `sampleRate` no retorno. | Deixar o Android lidar com dois formatos | O Android já tem um caminho de áudio só. Duas taxas no cliente é onde nascem chiado e travada. |

---

## 4. Restrições

| # | Restrição | Fonte |
|---|---|---|
| R1 | Nenhum parâmetro da tabela "Parâmetros Críticos de Áudio" muda (48 kHz, frame 960, thresholds de VAD, debounce 300 ms, min AI speech 400 ms). | CLAUDE.md |
| R2 | Chaves só via variável de ambiente / `local.properties`. Nenhuma chave em código, log, teste ou commit. | CLAUDE.md + regra pessoal |
| R3 | Nenhum log pode imprimir o valor da chave; diagnóstico só via nome da fonte (`resolveApiKeySource()`). | `elevenLabsClient.js:133` |
| R4 | O app precisa continuar funcionando **sem** `CARTESIA_API_KEY` configurada: nesse caso o comportamento é exatamente o de hoje (texto puro). | Compatibilidade com o ambiente atual |
| R5 | Sem framework de teste novo: testes são scripts Node com `assert`, no estilo de `test_llm_failover.js`. | Convenção do repo |
| R6 | Backend é ES Modules (`"type": "module"`). Nada de `require()`. | `backend_nodejs/package.json` |
| R7 | O primeiro áudio do fallback deve sair em ≤ 4 s após a falha do primário; passou disso, degrada para texto. | Meta de UX de conversa |

---

## 5. Interfaces e contratos de dados

### 5.1 Funções / módulos

~~~js
// backend_nodejs/services/ttsProvider.js
/** @typedef {'elevenlabs'|'cartesia'} ProviderName */

/** Erro de chave/cota — provedor deve entrar em cooldown. */
export function isTtsAuthOrQuotaError(err: unknown): boolean

/** Ordem de tentativa agora, já filtrando provedores em cooldown e sem chave. */
export function preferredTtsProviderOrder(): ProviderName[]

export function markProviderUnavailable(name: ProviderName, reason?: string, cooldownMs?: number): void
export function isProviderAvailable(name: ProviderName): boolean

/** Estado para /health/tts — nunca inclui a chave, só booleano + fonte. */
export function ttsProviderStatus(): {
  order: ProviderName[],
  providers: Array<{ name: ProviderName, hasKey: boolean, keySource: string|null, cooldownUntil: number|null }>
}
~~~

~~~js
// backend_nodejs/services/cartesiaClient.js
export function hasCartesiaKey(): boolean
export function resolveCartesiaVoiceId(): string   // env CARTESIA_VOICE_ID, com default no código

/** Mesma forma de retorno de elevenLabsClient.synthesizePcmRest(). */
export async function synthesizePcmRest(text: string, voiceId?: string):
  Promise<{ pcm: Buffer, voiceId: string, sampleRate: number }>
~~~

### 5.2 Eventos / rotas / payloads

| Direção | Evento ou rota | Payload | Quando dispara |
|---|---|---|---|
| Backend → Android | `tts_status` | `{ provider: 'elevenlabs'\|'cartesia', voiceId, fallback: boolean }` | Antes do primeiro `audio_chunk` de cada resposta |
| Backend → Android | `tts_unavailable` | `{ reason, mode: 'text_only', clientFallback: boolean }` | Só quando **todos** os provedores falharam |
| Backend → Android | `audio_chunk` | Base64 (frames Opus) — **inalterado** | Igual hoje, venha do provedor que vier |
| HTTP | `GET /health/tts` | `{ ok, order, providers:[{name, hasKey, keySource, cooldownUntil}] }` | Diagnóstico manual |

**Taxonomia fechada de `reason`** (o Android decide o texto do toast a partir dela):

| `reason` | Significado | `clientFallback` |
|---|---|---|
| `elevenlabs_auth_failed` | 401/403, ou 400 com `authentication_error` | `false` |
| `elevenlabs_quota_exceeded` | 429 / cota estourada | `false` |
| `cartesia_auth_failed` | idem, no secundário | `false` |
| `no_provider_configured` | nenhuma chave presente | `true` |
| `all_providers_failed` | todos tentados e falharam | `false` |
| `first_audio_byte_timeout` | watchdog estourou (já existe hoje) | `true` |

### 5.3 Variáveis de ambiente

| Nome | Obrigatória? | Default | Onde é lida |
|---|---|---|---|
| `ELEVENLABS_API_KEY` | não (sem ela, primário é pulado) | — | `elevenLabsClient.apiKey()` |
| `CARTESIA_API_KEY` | não (sem ela, secundário é pulado) | — | `cartesiaClient.hasCartesiaKey()` |
| `CARTESIA_VOICE_ID` | não | id definido no código | `cartesiaClient.resolveCartesiaVoiceId()` |
| `TTS_PROVIDER_COOLDOWN_MS` | não | `600000` | `ttsProvider.js` |

> ⚠️ Só o **nome** das variáveis aparece aqui e no código. Os valores vivem no painel do
> Render e no `local.properties`, que está no `.gitignore`.

---

## 6. Dependências

- Bibliotecas novas: **nenhuma** (D3 — `fetch` nativo do Node 20).
- Serviço externo: API REST do Cartesia — conta e chave criadas **antes** do ciclo,
  pelo painel do provedor. Custo por caractere; só é usado em degradação.
- Ordem: nada precede esta spec. `render.yaml` / painel do Render ganha
  `CARTESIA_API_KEY` — passo manual seu, ⚠️ nunca do agente.

---

## 7. Casos extremos

| # | Situação | Comportamento esperado |
|---|---|---|
| E1 | Chave ElevenLabs inválida (o caso do print) | Áudio sai pelo Cartesia; `tts_status` com `fallback:true`; ElevenLabs em cooldown de 10 min |
| E2 | Nenhuma das duas chaves configurada | `tts_unavailable` com `reason:'no_provider_configured'`, `clientFallback:true`; texto preservado |
| E3 | ElevenLabs cai **no meio** do streaming (já saiu áudio) | Não recomeça pelo Cartesia: encerra o turno, `estado_ia:'ociosa'`. Reiniciar a fala pela metade é pior que cortar |
| E4 | Barge-in durante a fala do provedor secundário | Cancela igual ao primário; `bargeInHandler` não muda |
| E5 | Cartesia devolve áudio vazio ou < 100 bytes | Trata como falha do provedor; `all_providers_failed` |
| E6 | Cartesia responde com sample rate diferente do pedido | Usa o `sampleRate` retornado no header/JSON, não um valor fixo |
| E7 | Os dois provedores em cooldown ao mesmo tempo | Pula direto para texto, sem tentar rede (latência zero de degradação) |
| E8 | Processo reinicia (Render free dorme) | Cooldown é em memória e some — comportamento aceito e documentado |
| E9 | `shadow_speak` (tela Echo) com primário morto | Mesma cadeia do chat: o failover mora em `ttsProvider.js`, não duplicado nos dois handlers |

---

## 8. Questões em aberto

| # | Pergunta | Bloqueia a entrega? | Resposta |
|---|---|---|---|
| Q1 | O aviso "voz reserva" aparece como toast ou só como log? | não | |
| Q2 | Cooldown de 10 min é bom para uso de 1 pessoa, ou 30 min como no Claude? | não | |
| Q3 | Qual voz do Cartesia soa mais próxima do General American do Brian? | não (default no código, ajusta por env depois) | |

---

## 9. Critérios de aceitação (verificáveis)

| # | Critério | Como verificar |
|---|---|---|
| A1 | `node test_tts_failover.js` passa e cobre E1, E2, E5, E7 | `cd backend_nodejs; node test_tts_failover.js` |
| A2 | `npm run test:unit` continua verde e inclui o novo teste | `cd backend_nodejs; npm run test:unit` |
| A3 | `GET /health/tts` responde 200 com os dois provedores e **sem** nenhuma chave no corpo | `Invoke-RestMethod http://localhost:3000/health/tts \| ConvertTo-Json -Depth 5` |
| A4 | Com `ELEVENLABS_API_KEY="chave-invalida"` e Cartesia válida, o device toca áudio | Rodar backend local, falar uma frase no app, ouvir a resposta |
| A5 | Sem nenhuma chave de TTS, o app mostra texto e não trava spinner | Subir backend sem as duas variáveis; mandar mensagem |
| A6 | `git grep -nE "sk_\|xi-api-key: *['\"][A-Za-z0-9]" -- backend_nodejs` não retorna nada | comando |
| A7 | Nenhum arquivo do pipeline de áudio mudou | `git diff --stat main -- backend_nodejs/audioEncoder.js app/src/main/java/com/roberto/eliasaitutor/audio/` vazio |
| A8 | Barge-in continua cortando a fala em ≤ 500 ms com o provedor secundário ativo | Teste manual no device: falar por cima do Elias |

---

## 10. Registro de mudanças da spec

| Data | O que mudou | Origem |
|---|---|---|
| 2026-08-26 | Criação | Print do device com `authentication_error` na ElevenLabs |
