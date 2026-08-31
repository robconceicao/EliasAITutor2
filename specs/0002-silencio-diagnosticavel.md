# SPEC-0002 — Silêncio diagnosticável com a ElevenLabs como única voz

> Status: `rascunho`
> Autor: Roberto · Criada em: 2026-08-26
> Branch de trabalho: `feat/0002-silencio-diagnosticavel`
> ADR: `specs/decisions/ADR-0002-elevenlabs-voz-unica.md` · Revoga: `specs/0001-tts-provider-failover.md`

---

## 1. Objetivo

Quando o Elias emudecer, descobrir **por quê** em menos de um minuto, sem abrir log de servidor.

**Problema observado:**
- App mudo em 2026-08-26 com `ElevenLabs REST TTS 400: {"detail":{"type":"authentication_error"}}`.
- `/health` respondeu `elevenLabsKey: true` — porque a chave **existe**. Ela só não **funciona**.
  Uma chave morta é indistinguível de uma boa no único diagnóstico que o sistema oferece.
- O toast do app diz "Voz indisponível (…)" com o erro cru da API, que não diz ao usuário o que fazer.

**Como saberemos que resolveu:**
- Com a chave inválida, `/health/tts` responde em uma linha que a credencial foi **recusada**, e o app
  mostra uma mensagem que distingue "problema de servidor" de "sem internet".

---

## 2. Escopo

- [ ] `GET /health/tts`: além de haver chave, dizer se ela **funciona** — checagem real contra a API,
      com resultado em cache curto para não virar custo por request.
- [ ] Taxonomia fechada de `reason` em `tts_unavailable` (herdada da SPEC-0001, seção 5.2).
- [ ] `EliasViewModel`: mensagem por `reason`, sem repetir o corpo cru do erro da API.
- [ ] Log de boot: dizer se a chave presente foi aceita, não só se existe.

### 2.2 Interfaces

~~~js
// backend_nodejs/services/elevenLabsClient.js  (adições)
/**
 * Sonda em duas camadas. Nunca lança; nunca loga a chave.
 *   1. GET /v1/user — grátis e sem efeito colateral. 200 → chave plena, acabou.
 *   2. Se 401/403, o resultado é INCONCLUSIVO: uma chave com escopo restrito a TTS
 *      legitimamente não lê dados da conta. Só então faz uma síntese de 1 caractere,
 *      que é a capacidade que o app realmente precisa.
 * `method` diz qual camada respondeu, para o diagnóstico não virar adivinhação.
 */
export async function verifyApiKey({ timeoutMs }): Promise<{
  ok: boolean, status: number|null, error: string|null,
  method: 'account'|'tts'|'none'
}>

/** Mesma sonda, com cache curto (TTS_KEY_PROBE_CACHE_MS, default 60 s). */
export async function verifyApiKeyCached(): Promise<{ ok, status, error, checkedAt: number, cached: boolean }>
~~~

~~~js
// backend_nodejs/services/ttsProvider.js — já implementado, agora ligado ao server.js
ttsFailureReason(err) · noteTtsFailure(err) · clearTtsFailure() · ttsStatus()
~~~

| Direção | Evento / rota | Payload |
|---|---|---|
| Backend → Android | `tts_unavailable` | `{ reason, mode:'text_only', clientFallback }` — `reason` **sempre** da taxonomia fechada, nunca o corpo do erro |
| HTTP | `GET /health/tts` | `{ ok, hasKey, keySource, state, lastFailure, liveCheck:{ ok, status, checkedAt, cached } }` |

Taxonomia fechada de `reason`: `no_key_configured` · `elevenlabs_auth_failed` ·
`elevenlabs_quota_exceeded` · `first_audio_byte_timeout` · `tts_failed`.

`first_audio_byte_timeout` já era emitido pelo watchdog antes desta spec e é informação
útil (a chave está boa, o áudio é que não veio) — entra na taxonomia em vez de ser
achatado em `tts_failed`.

### 2.1 Não-escopo

- **Segundo provedor de TTS. Em nenhuma forma.** (ADR-0002)
- Cooldown / bench de provedor — removido por decisão, ver ADR-0002.
- Pipeline de áudio: `audioEncoder.js`, `OpusAudioPlayer.kt`, `JitterBuffer.kt`, `LocalVAD.kt`.
- Barge-in, turn-taking, Modo Programa, seeds, migrations.
- Rotação automática de chave — é operação, não código.

---

## 3. Decisões

| # | Decisão | Alternativa descartada | Por que |
|---|---|---|---|
| D1 | A checagem de chave é **sob demanda com cache curto**, não a cada turno de fala. | Validar a chave em todo request de TTS | Uma chamada extra por fala é custo e latência no caminho feliz, para responder algo que muda raramente. |
| D2 | O app decide a mensagem pela `reason`, nunca pelo texto cru da API. | Repassar `e.message` ao toast | O corpo do erro da ElevenLabs é para desenvolvedor. O usuário precisa saber se a culpa é do servidor ou da rede dele. |
| D3 | A sonda **não** pode concluir "chave recusada" a partir de um 401 num endpoint de conta. | Sondar só `GET /v1/user` | Evidência de 2026-08-26: a chave de produção do Elias responde 401 em `/v1/user` e `/v1/voices`, e **funciona** para TTS — é uma chave com escopo restrito. Uma sonda que só olha a conta reprovaria a chave boa, e o diagnóstico mentiria na direção oposta ao problema que ele existe para resolver. |
| D4 | A camada 2 gasta 1 caractere de cota por sonda, e só roda quando a camada 1 é inconclusiva. | Nunca gastar cota | Sem uma chamada de TTS de verdade não há como distinguir "chave morta" de "chave com escopo de TTS". 1 caractere a cada 60 s no pior caso é preço aceitável por um diagnóstico que não mente. |

---

## 4. Restrições

| # | Restrição | Fonte |
|---|---|---|
| R1 | Nenhum parâmetro da tabela crítica de áudio muda. | CLAUDE.md |
| R2 | Nenhum valor de chave em código, log, teste, resposta HTTP ou commit. | CLAUDE.md |
| R3 | `/health/tts` não pode expor a chave nem parte dela — só o nome da env var. | SPEC-0001, R3 |
| R4 | Sem dependência nova; testes como scripts Node com `assert`. | Convenção do repo |

---

## 5. Questões em aberto

| # | Pergunta | Bloqueia? | Resposta |
|---|---|---|---|
| Q1 | Quanto tempo de cache para o resultado da checagem — 60 s? 5 min? | não | **Provisório: 60 s**, ajustável por `TTS_KEY_PROBE_CACHE_MS`. Escolhi um default em vez de travar a entrega; troque o valor quando decidir. |
| Q2 | A checagem entra também no `/health` geral, ou só em `/health/tts`? | não | **Provisório: só em `/health/tts`.** O `/health` é lido pelo health check do Render a cada poucos segundos; uma chamada de rede ali vira custo recorrente. |
| Q3 | O app deve mostrar um aviso persistente enquanto a voz estiver fora, ou só o toast? | não | |

---

## 6. Critérios de aceitação

| # | Critério | Como verificar |
|---|---|---|
| A1 | Com `ELEVENLABS_API_KEY` inválida, `/health/tts` responde que a chave foi **recusada** | `Invoke-RestMethod http://localhost:3000/health/tts` |
| A2 | Com chave válida, responde que está operacional | idem |
| A6 | Chave com escopo restrito a TTS (401 na conta, TTS OK) é reportada como **operacional**, não como recusada | coberto por `test_tts_failover.js` |
| A3 | Nenhuma resposta contém a chave nem fragmento dela | inspeção do corpo + teste |
| A4 | O app distingue "voz recusada pelo servidor" de "sem conexão" | teste manual no device |
| A5 | `npm run test:unit` verde | `cd backend_nodejs; npm run test:unit` |

---

## 7. Registro de mudanças

| Data | O que mudou | Origem |
|---|---|---|
| 2026-08-26 | Criação, substituindo a SPEC-0001 | Decisão de manter a ElevenLabs como única voz + app ainda mudo |
| 2026-08-26 | 2.2 declara as interfaces (`verifyApiKey`, `verifyApiKeyCached`, payload de `/health/tts`) | Ciclo 3, antes de escrever o código |
| 2026-08-26 | Q1 e Q2 respondidas com defaults provisórios e ajustáveis, em vez de bloquear a entrega | Ciclo 3 |
| 2026-08-26 | D3/D4 e A6: sonda vira duas camadas — 401 na conta deixa de significar chave ruim | Evidência real do device: a chave de produção é TTS-scoped e a sonda de camada única a reprovava |
