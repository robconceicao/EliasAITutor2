# Spec-Driven Development com dois agentes — guia prático para o Elias AI Tutor

> Para quem nunca fez SDD. Do zero até o primeiro ciclo completo rodando,
> usando o repositório real do Elias, no Windows/PowerShell.

---

## Premissas que assumi

O gabarito deste guia pedia dados do app-alvo. Em vez de te devolver perguntas e travar,
preenchi com o que o repositório e o `CLAUDE.md` já dizem. **Confira esta caixa antes de seguir**;
se algo estiver errado, corrija aqui e o resto do guia continua válido.

| Campo | O que assumi |
|---|---|
| App-alvo | **Elias AI Tutor** — tutor de inglês gamificado, conversa por voz |
| Stack | Kotlin + Jetpack Compose (`app/`) · Node.js + Express + Socket.io, ES Modules (`backend_nodejs/`) · Supabase (perfil/gamificação) · MongoDB opcional (estado do programa) |
| Repositório | **mono-repo** `EliasAITutor2`: app Android e backend na mesma árvore |
| Estado atual | Em produção no Render (free), uso real de 1 pessoa. Testes existem como scripts Node com `assert` (`backend_nodejs/test_*.js`), sem framework. Sem CI. |
| Próxima entrega | **Failover de provedor de TTS**: quando a chave da ElevenLabs falha, o Elias fala por um segundo provedor em vez de emudecer — exatamente o erro `authentication_error` do print de 2026-08-26 |
| Restrições reais | ~1 h/dia · custo por chamada de LLM/TTS importa · há usuário em produção, então `main` precisa ficar sempre publicável |
| Agentes | **Escritor:** Claude Code. **Verificador:** um segundo CLI de outra família de modelo (Gemini CLI ou Codex CLI), rodando sobre o mesmo repositório |

Se o seu verificador for outro CLI, só troca o nome do executável nos comandos — a mecânica é idêntica.

---

## 1. Visão geral do loop

    spec ──▶ escritor ──▶ verificador ──▶ atualização da spec ──▶ (próxima spec)
      ▲                                          │
      └──────────────────────────────────────────┘

1. **spec → escritor.** O agente escreve muito rápido e decide muito mal quando falta contexto.
   A spec é a fronteira: sem ela, cada dúvida vira uma invenção silenciosa que você só descobre
   no device, três dias depois.
2. **escritor → verificador.** Quem escreveu o código é a pior pessoa para julgá-lo: o mesmo
   raciocínio que produziu o erro vai ler o erro e achar bom. Por isso o verificador é um
   **modelo de outra família** — ele erra em lugares diferentes.
3. **verificador → spec.** Aqui está o coração do método. Todo achado é classificado como
   **bug de código** (a spec era clara, o código falhou) ou **lacuna de spec** (o código é
   defensável, a spec era omissa). Bug se conserta no código. Lacuna se conserta **na spec, primeiro**.
4. **spec → próximo ciclo.** A spec corrigida entra no ciclo seguinte já sabendo o que
   confundiu o agente antes. É assim que o sistema aprende — e é por isso que a seta volta.

Sem a seta de volta, você não tem SDD: tem um agente escrevendo código e outro reclamando.

---

## Fase 0 — Preparar o repositório

**Por quê.** O agente lê o repositório, não a sua cabeça. Tudo que você "sabe" e não está
escrito em arquivo será re-inventado a cada sessão, de forma diferente. A Fase 0 é
transformar conhecimento tácito em arquivo versionado.

### 0.1 Abrir o terminal no projeto

Defina a variável uma vez por sessão (ajuste o caminho para onde o repo está na sua máquina):

~~~powershell
$Elias = "$HOME\StudioProjects\EliasAITutor2"
Set-Location $Elias
Test-Path .\CLAUDE.md          # precisa responder True
git status --short
~~~

Se `Test-Path` responder `False`, você está na pasta errada — corrija `$Elias` antes de continuar.

### 0.2 Criar a estrutura

~~~powershell
New-Item -ItemType Directory -Force -Path .\specs, .\specs\decisions, .\specs\findings | Out-Null
Get-ChildItem .\specs -Recurse -Name
~~~

Estrutura final:

| Caminho | Papel |
|---|---|
| `specs/TEMPLATE_SPEC.md` | modelo em branco |
| `specs/NNNN-slug.md` | uma entrega, uma spec |
| `specs/decisions/ADR-NNNN-*.md` | decisão de arquitetura com alternativas descartadas |
| `specs/findings/` | relatórios do verificador |
| `CLAUDE.md` | contexto e limites do **escritor** |
| `VERIFIER.md` | contexto e limites do **verificador** |
| `.gitignore` | garante que segredo nenhum vira commit |

### 0.3 Os arquivos já estão criados neste repositório

Este guia veio acompanhado dos arquivos prontos e preenchidos para o Elias:

~~~powershell
Get-ChildItem .\specs -Recurse -Name
Get-Content .\VERIFIER.md -TotalCount 12
~~~

- `specs/README.md` — as cinco regras da pasta.
- `specs/TEMPLATE_SPEC.md` — o template da Fase 1.
- `specs/0001-tts-provider-failover.md` — a spec preenchida da sua próxima entrega.
- `specs/decisions/ADR-0001-tts-provider-failover.md` — a decisão por trás dela.
- `specs/findings/TEMPLATE_RELATORIO.md` — o formato do relatório da Fase 4.
- `VERIFIER.md` — o contrato do verificador (Fase 2).
- `specs/BACKLOG.md` — onde vão os achados fora de escopo (Fase 5).
- `specs/METRICAS.md` — a planilha de M1 a M6 (seção Métricas).
- `.claude/settings.json` — as permissões do escritor (Fase 2).
- `scripts/hooks/pre-push` — o guardrail que bloqueia push em `main` (Fase 6).
- `CLAUDE.md` e `AGENTS.md` — já editados: veja 0.4 e 0.5 e revise o diff dos dois.

### 0.4 `CLAUDE.md` — o contexto do escritor

Seu `CLAUDE.md` já é bom: descreve stack, pipeline de áudio, eventos Socket.io, parâmetros
críticos e uma seção "O que NÃO fazer". Faltava **plugar o SDD nele** — este bloco já foi
acrescentado ao final do arquivo (⚠️ revise o diff):

~~~markdown
## Modo Spec-Driven (SDD)

Antes de escrever código nesta sessão:
1. Leia a spec ativa em `specs/` indicada no meu pedido. Se eu não indicar nenhuma, pergunte.
2. Escreva apenas o que a seção **Escopo** autoriza. A seção **Não-escopo** é proibição literal.
3. Ao esbarrar numa **Questão em aberto** da spec, **pare e pergunte**. Não escolha por mim.
4. Ao terminar, mostre `git diff --stat` e diga qual critério de aceitação cada arquivo atende.
5. Nunca commite. Eu reviso o diff e commito.
6. Nunca escreva valores de chave/token em código, teste, log ou commit — só nomes de variável de ambiente.
~~~

### 0.5 `AGENTS.md` — não sobrescreva, aponte

`AGENTS.md` existe e tem uma seção `## Flutter` (linha ~55) que não se aplica a um app Compose —
o `CLAUDE.md` inclusive manda não usá-lo como referência de stack. Vários CLIs leem `AGENTS.md`
automaticamente, então ele não pode ficar mentindo. A correção mínima e segura é **um aviso no topo**,
sem apagar o histórico do arquivo — já aplicado:

~~~markdown
> ⚠️ **Leia este aviso antes de usar o restante do arquivo.**
> A seção `## Flutter` (linha ~55) não se aplica: a UI do Elias é **Jetpack Compose**, não Flutter.
> Stack real: Kotlin + Jetpack Compose (`app/`) e Node.js + Socket.io, ES Modules (`backend_nodejs/`).
> Referência correta de arquitetura, pipeline de áudio e parâmetros críticos: `CLAUDE.md`.
>
> Contratos por papel:
> - agente **escritor** → `CLAUDE.md` + a spec ativa em `specs/`
> - agente **verificador** → `VERIFIER.md`
> - método de trabalho (SDD) → `docs/SDD_GUIA_AGENTES.md`
~~~

⚠️ São os dois arquivos que **todos** os agentes leem. Leia o diff dos dois antes de commitar —
um erro aqui se propaga para todas as sessões futuras.

### 0.6 `.gitignore` — o degrau que faltava

O `.gitignore` protegia `local.properties`, mas **não** listava `.env` — e o `CLAUDE.md` diz
explicitamente para nunca commitar `.env`. Um agente rodando por horas cria arquivo de ambiente
sem avisar. Esse buraco já foi fechado:

~~~powershell
Select-String -Path .\.gitignore -Pattern "^\.env" -SimpleMatch
git ls-files | Select-String -Pattern "\.env|local\.properties"   # precisa vir VAZIO
~~~

O segundo comando vindo vazio é a prova de que nenhum segredo está versionado. Rode-o de novo
sempre que uma sessão longa terminar.

### ✅ Critério de pronto da Fase 0

Os quatro comandos abaixo passam:

~~~powershell
Test-Path .\specs\TEMPLATE_SPEC.md, .\specs\decisions, .\specs\findings, .\VERIFIER.md
Select-String -Path .\CLAUDE.md -Pattern "Modo Spec-Driven" -SimpleMatch
Select-String -Path .\AGENTS.md  -Pattern "Leia este aviso" -SimpleMatch
git ls-files | Select-String -Pattern "\.env|local\.properties"   # vazio
~~~

Traduzindo: existe pasta de specs, o escritor sabe que está em modo SDD, o `AGENTS.md`
não engana mais ninguém e nenhum segredo está rastreado pelo git.

---

## Fase 1 — Escrever a primeira spec

**Por quê.** Uma spec não é documentação: é o **contrato de escopo** do agente. Ela existe para
responder, antes da primeira linha de código, as três perguntas que o agente responderia sozinho
e errado: *o que está fora?*, *o que é proibido mexer?*, *como sabemos que acabou?*

Regra prática: se escrever a spec leva mais de 40 minutos, a entrega está grande demais.
Corte pela metade e faça duas.

### 1.1 O template completo

Está em `specs/TEMPLATE_SPEC.md`. Conteúdo integral:

~~~~markdown
# SPEC-NNNN — <título curto da entrega>

> Status: `rascunho` | `aprovada` | `em execução` | `entregue` | `revogada`
> Autor: Roberto · Criada em: AAAA-MM-DD · Última atualização: AAAA-MM-DD
> Branch de trabalho: `feat/NNNN-slug`
> ADRs relacionadas: `specs/decisions/ADR-NNNN-*.md`

---

## 1. Objetivo

Uma frase que descreve a mudança do ponto de vista do usuário do app.
Se você não consegue escrever em uma frase, a entrega está grande demais — quebre em duas specs.

**Problema observado (com evidência):**
- Onde aconteceu, o que o usuário viu, print/log/linha de código.

**Como saberemos que resolveu:**
- Descrição observável do "depois".

---

## 2. Escopo

Lista fechada do que ESTA spec autoriza mexer.

- [ ] Item de escopo 1 — arquivo(s) alvo
- [ ] Item de escopo 2 — arquivo(s) alvo

### 2.1 Não-escopo (explícito)

O que o agente **não** pode fazer nesta entrega, mesmo que pareça uma boa ideia.
Esta seção é a que evita 80% do retrabalho.

- Não mexer em `<arquivo/área>` — motivo.
- Não refatorar `<coisa>` — fica para a SPEC-NNNN+1.

---

## 3. Decisões de arquitetura (com justificativa)

| # | Decisão | Alternativa descartada | Por que |
|---|---|---|---|
| D1 | | | |
| D2 | | | |

> Decisão que muda o formato de dados, contrato de rede ou custo recorrente
> vira também uma ADR em `specs/decisions/`.

---

## 4. Restrições

Regras não-negociáveis que o código precisa respeitar. Cite a fonte (CLAUDE.md, medição, contrato externo).

| # | Restrição | Fonte |
|---|---|---|
| R1 | | |
| R2 | | |

---

## 5. Interfaces e contratos de dados

Assinaturas exatas. O agente escritor não deve inventar nomes de campo.

### 5.1 Funções / módulos

~~~
// caminho/do/arquivo.js
export function nome(args): TipoDeRetorno
~~~

### 5.2 Eventos / rotas / payloads

| Direção | Evento ou rota | Payload | Quando dispara |
|---|---|---|---|
| | | | |

### 5.3 Variáveis de ambiente

| Nome | Obrigatória? | Default | Onde é lida |
|---|---|---|---|

> ⚠️ Nunca escreva o **valor** de uma chave aqui. Só o nome da variável.

---

## 6. Dependências

- Bibliotecas novas (nome, versão, licença, tamanho): …
- Serviços externos e custo por chamada: …
- Ordem de execução: o que precisa existir antes desta spec?

---

## 7. Casos extremos

Lista numerada. Cada linha vira depois um teste ou um critério de aceitação.

| # | Situação | Comportamento esperado |
|---|---|---|
| E1 | | |
| E2 | | |

---

## 8. Questões em aberto

Perguntas que você ainda não decidiu. O agente **deve parar e perguntar** ao esbarrar numa delas,
em vez de escolher sozinho.

| # | Pergunta | Bloqueia a entrega? | Resposta (preencher depois) |
|---|---|---|---|
| Q1 | | sim/não | |

---

## 9. Critérios de aceitação (verificáveis)

Cada item precisa ser checável por um comando ou por uma observação binária no app.
Nada de "funciona bem" ou "está rápido".

| # | Critério | Como verificar |
|---|---|---|
| A1 | | comando / passo no app |
| A2 | | |

---

## 10. Registro de mudanças da spec

| Data | O que mudou | Origem (achado do verificador, teste em device, decisão) |
|---|---|---|
| | | |
~~~~

#### Como cada seção protege você

| Seção | O erro que ela evita |
|---|---|
| Objetivo em uma frase | Entrega que cresce no meio do caminho |
| **Não-escopo** | O agente "aproveitando" para refatorar o jitter buffer |
| Decisões + justificativa | Você reabrir a mesma discussão daqui a três semanas |
| Restrições | Agente mexendo nos 48 kHz porque "ficaria mais simples" |
| Interfaces e contratos | Nome de campo inventado que o Android não sabe ler |
| Casos extremos | O bug que só aparece no device, no meio da aula |
| Questões em aberto | O agente decidindo por você e escondendo a decisão no código |
| Critérios de aceitação | "Ficou pronto?" virar opinião em vez de comando |

### 1.2 O template preenchido com a sua próxima entrega

Esta é a spec real, já commitada em `specs/0001-tts-provider-failover.md`. Ela nasce do print do
device: a resposta do Elias apareceu escrita e muda, com
`ElevenLabs REST TTS 400: {"detail":{"type":"authentication_err…`.

O diagnóstico que motivou a spec veio de ler o código, não de adivinhar:
toda a cadeia de fallback de voz — voz principal → voz reserva
(`openTtsWebSocketWithFallback`, `elevenLabsClient.js:279`) → REST completo
(`synthesizePcmRest`, `elevenLabsClient.js:346`) → texto puro — usa **a mesma chave da mesma conta**.
E o "fallback de emergência" do Android (`network/ElevenLabsApi.kt`) também é ElevenLabs.
A rede de segurança inteira depende do fio que arrebentou.

~~~~markdown
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
~~~~

> ⚠️ Antes de mandar essa spec para o escritor, leia a seção **Questões em aberto** e decida
> se alguma delas virou bloqueante para você. Q1–Q3 estão marcadas como não-bloqueantes de propósito:
> nenhuma delas impede o código de existir.

### ✅ Critério de pronto da Fase 1

- A spec cabe em uma frase na seção 1 e você consegue repeti-la de cabeça.
- Toda linha da seção 9 (critérios) é um **comando** ou uma **observação binária no app**.
  Se você não consegue dizer "passou/não passou" sem discutir, reescreva o critério.
- A seção 2.1 (não-escopo) nomeia arquivos concretos, não categorias vagas.
- Nenhum valor de chave aparece na spec — só nomes de variável.

---

## Fase 2 — Configurar os dois agentes

**Por quê.** Os dois agentes leem o mesmo repositório, mas têm funções opostas. Se os dois
puderem escrever, o verificador deixa de ser verificador: vira um segundo escritor que
"melhora" o que o primeiro fez, e você perde a única opinião independente da mesa.

A separação se faz em três camadas, do mais fraco ao mais forte:

| Camada | O que faz | Força |
|---|---|---|
| Arquivo de contexto (`CLAUDE.md`, `VERIFIER.md`) | Diz ao agente qual é o papel dele | Persuasão |
| Permissões do CLI (`.claude/settings.json`, flag de sandbox) | Bloqueia ferramentas | Mecânica |
| **Worktree separada** | O verificador nem enxerga sua árvore de trabalho | Física |

Use as três. A terceira é a que realmente te protege.

### 2.1 Escritor — Claude Code

Contexto: `CLAUDE.md` (já com o bloco "Modo Spec-Driven" da Fase 0) + a spec ativa.

Permissões: o arquivo `.claude/settings.json` já foi criado neste repositório com esta política:

~~~json
{
  "permissions": {
    "deny": [
      "Read(./.env)", "Read(./.env.*)", "Read(./backend_nodejs/.env)",
      "Read(./local.properties)", "Edit(./local.properties)",
      "Edit(./render.yaml)", "Edit(./backend_nodejs/seeds/**)", "Edit(./.gitignore)",
      "Bash(git push:*)", "Bash(git reset:*)", "Bash(git checkout:*)"
    ],
    "allow": [
      "Bash(git status:*)", "Bash(git diff:*)", "Bash(git log:*)",
      "Bash(node backend_nodejs/test_*.js)", "Bash(npm run test:unit)"
    ]
  }
}
~~~

Leia a lista `deny` como uma frase: **o escritor não lê segredo, não mexe em infra e não empurra
nada para o remoto — o push é seu.** É a sua disciplina atual, escrita de um jeito que a máquina
consegue cumprir mesmo às 2 da manhã.

`git commit` fica **fora** do `deny` de propósito. Bloquear os dois parece mais seguro e não é: o
trabalho fica pendurado na árvore, um hook de fim de sessão cobra um commit que o agente não pode
fazer, e você acaba liberando tudo no susto. Commit na branch é reversível e não sai da sua máquina;
**push é o passo irreversível**, e é nele que o guardrail precisa estar. Se preferir a versão
estrita, acrescente `"Bash(git commit:*)"` ao `deny` — sabendo que troca uma barreira por fricção.

Abrir o escritor na branch da spec:

~~~powershell
Set-Location $Elias
git checkout main
git pull origin main
git checkout -b feat/0001-tts-provider-failover
claude
~~~

### 2.2 Verificador — segundo CLI, em worktree própria

A worktree é uma segunda pasta com o mesmo repositório, apontando para a sua branch.
O verificador trabalha lá: ele lê exatamente o código que você escreveu, mas um erro dele
não encosta na sua árvore.

~~~powershell
Set-Location $Elias
git worktree add --detach ..\elias-verify HEAD
Set-Location ..\elias-verify
git log --oneline -1                         # confirma que é o commit que você quer auditar
Get-Content .\VERIFIER.md -TotalCount 5      # confirma que o contrato está lá
~~~

O `--detach` não é detalhe: o git recusa duas worktrees na **mesma branch**, e o escritor está com ela
aberta. `HEAD` destacado dá ao verificador exatamente o commit em revisão, e ainda o impede de "avançar"
a branch por acidente.

Agora suba o verificador **em modo somente-leitura**. Confirme a flag exata da sua versão
com `--help` — os nomes mudam entre releases:

~~~powershell
# Opção A — Codex CLI
codex --help | Select-String -Pattern "sandbox|approval"
codex exec --sandbox read-only "Leia VERIFIER.md e aguarde minhas instruções."

# Opção B — Gemini CLI
gemini --help | Select-String -Pattern "approval|yolo|sandbox"
gemini -p "Leia VERIFIER.md e aguarde minhas instruções."
~~~

⚠️ **Nunca** ligue auto-aprovação (`--yolo`, `--dangerously-*`, `-y`) no verificador.
Ele não precisa escrever nada além do relatório — e o relatório você pode salvar você mesmo.

Quando o ciclo terminar, desmonte a worktree:

~~~powershell
Set-Location $Elias
git worktree remove ..\elias-verify
~~~

### 2.3 Quem escreve onde

| | Escritor (Claude Code) | Verificador (2º CLI) |
|---|---|---|
| Contexto | `CLAUDE.md` + spec ativa | `VERIFIER.md` + spec ativa |
| Pasta | `$Elias` (sua árvore) | `..\elias-verify` (worktree) |
| Pode escrever | código dentro do escopo da spec | **nada** além de `specs/findings/` |
| Pode commitar | sim, na branch da spec | não |
| Pode dar push | **não** | **não** |
| Roda testes | sim | sim (só os da seção 9 da spec) |
| Lê `.env` / `local.properties` | não | não |

### ✅ Critério de pronto da Fase 2

~~~powershell
Test-Path .\.claude\settings.json
git worktree list                    # precisa mostrar 2 linhas
~~~

E um teste de comportamento que vale mais que os dois comandos: peça ao verificador
*"crie um arquivo teste.txt na raiz"*. Ele deve **recusar**. Se ele criar, sua camada de
permissão não está ativa — resolva isso antes de rodar qualquer ciclo.

---

## Fase 3 — Primeiro ciclo curto (20 a 30 minutos)

**Por quê.** O primeiro ciclo não serve para entregar funcionalidade: serve para você
**ver o loop girar** e descobrir onde a sua spec era ambígua, com pouco código na mesa.
Por isso ele cobre só um pedaço da SPEC-0001 — a fatia que não toca em rede nenhuma.

**Fatia do ciclo 1:** apenas `services/ttsProvider.js` + `test_tts_failover.js`.
Sem Cartesia, sem `server.js`, sem Android. Lógica pura, testável sem chave, sem device.

### 3.1 O que eu digito para o escritor

Cole isto, literalmente, no Claude Code:

~~~text
Leia specs/0001-tts-provider-failover.md por inteiro antes de escrever qualquer código.

Neste ciclo você vai implementar SOMENTE a fatia abaixo:
- criar backend_nodejs/services/ttsProvider.js com as quatro funções da seção 5.1:
  isTtsAuthOrQuotaError, preferredTtsProviderOrder, markProviderUnavailable /
  isProviderAvailable, ttsProviderStatus;
- criar backend_nodejs/test_tts_failover.js cobrindo os casos extremos E1, E2, E5 e E7,
  no mesmo estilo de test_llm_failover.js (import + assert, sem framework, sem rede);
- adicionar o novo teste ao script test:unit em backend_nodejs/package.json.

NÃO crie cartesiaClient.js. NÃO toque em server.js. NÃO toque em nada dentro de app/.
NÃO faça requisição de rede em nenhum teste.

Restrições que valem para tudo: ES Modules, Node 20, nenhuma dependência nova,
nenhum valor de chave em código ou teste — apenas nomes de variável de ambiente.

Espelhe o padrão de cooldown que já existe em services/llmClient.js
(markClaudeUnavailable / shouldSkipClaude) em vez de inventar um novo.

Se esbarrar em qualquer Questão em Aberto da seção 8, PARE e me pergunte.

Ao terminar: rode `node test_tts_failover.js`, me mostre a saída,
mostre `git diff --stat` e diga qual critério de aceitação cada arquivo atende.
Não commite.
~~~

Repare no que esse prompt faz: dá a fatia, nomeia as proibições, aponta o padrão a imitar
e define o formato da entrega. Ele não explica *como* implementar — isso é trabalho do agente.

### 3.2 O que eu espero de saída

1. Dois arquivos novos e uma linha alterada em `package.json`.
2. `node test_tts_failover.js` imprimindo algo como `✅ tts failover tests passed`.
3. `git diff --stat` com **três** arquivos. Um quarto arquivo é sinal amarelo.
4. Uma frase ligando cada arquivo a A1/A2.

### 3.3 Como eu valido — ⚠️ revisão de diff

⚠️ **Este é o ponto de revisão obrigatório do ciclo.** Rode, na sua pasta, antes de aprovar:

~~~powershell
Set-Location $Elias
git diff --stat
git diff
Set-Location .\backend_nodejs
node test_tts_failover.js
npm run test:unit
Set-Location $Elias
git grep -nE "sk_|xi-api-key|api[_-]?key\s*=\s*[""'][A-Za-z0-9]{8}"
~~~

Checklist de leitura do diff — quatro perguntas, nesta ordem:

1. **Algum arquivo fora dos três?** Se sim, o agente saiu do escopo. Reverta o extra.
2. **O teste testa mesmo?** Um teste que só chama a função e não faz `assert` é decoração.
   Quebre uma condição de propósito e confirme que ele falha.
3. **Algum valor de chave apareceu?** O `git grep` acima é a rede; a leitura é a garantia.
4. **Algum parâmetro da tabela crítica do `CLAUDE.md` foi tocado?** Nesta fatia a resposta
   tem que ser não — nem `audioEncoder.js`, nem nada em `app/audio/`.

Passou nas quatro, commite **você**:

~~~powershell
git add backend_nodejs/services/ttsProvider.js backend_nodejs/test_tts_failover.js backend_nodejs/package.json
git commit -m "feat(tts): seleção de provedor com cooldown (SPEC-0001, fatia 1)"
~~~

### ✅ Critério de pronto da Fase 3

- `npm run test:unit` verde, com o novo teste incluído.
- Um commit seu, com no máximo três arquivos, mensagem citando `SPEC-0001`.
- `git grep` de segredos vazio.
- Você consegue apontar, na spec, qual critério de aceitação esse commit atende (A1 e A2).

---

## Fase 4 — Verificação

**Por quê.** O escritor acabou de te dizer que está tudo certo. Ele acredita nisso. O verificador
existe para responder uma pergunta que o escritor estruturalmente não consegue responder:
*o que a spec pedia e o código não faz?*

E, mais importante: **quando o código não faz porque a spec não pediu.** Essa distinção é o
motor do método inteiro. Sem ela, você fica corrigindo código para sempre e a spec nunca melhora.

### 4.1 O prompt do verificador

No terminal do verificador (na worktree `..\elias-verify`), cole:

~~~text
Você é o verificador independente deste repositório. Leia VERIFIER.md primeiro e siga-o à risca.
Você NÃO edita código. Você produz um relatório.

Spec sob verificação: specs/0001-tts-provider-failover.md
Diff sob verificação: `git diff main...HEAD`

Escopo deste ciclo (fatia 1): apenas services/ttsProvider.js, test_tts_failover.js
e a linha alterada em package.json. Qualquer outro arquivo tocado é DESVIO_DE_ESCOPO.

Faça, nesta ordem:
1. Liste os arquivos do diff e confirme se cada um está no escopo da seção 2 da spec.
2. Para CADA caso extremo da seção 7 que pertence a esta fatia (E1, E2, E5, E7):
   aponte a linha de código que o trata. Não encontrou? é achado.
3. Verifique cada restrição da seção 4 (R1 a R7) contra o diff.
4. Rode os comandos dos critérios A1 e A2 e cole a saída REAL como evidência.
5. Rode a busca por segredo do item 5 do VERIFIER.md.
6. Avalie se os testes realmente testam: existe assert com valor esperado, ou só chamada de função?

Para cada achado, classifique obrigatoriamente como BUG_DE_CODIGO, LACUNA_DE_SPEC,
DESVIO_DE_ESCOPO ou RUIDO — e RUIDO você descarta sem reportar.

Regra de desempate: se dois programadores competentes, lendo só a spec, pudessem ter
escrito código diferente e ambos estariam certos, é LACUNA_DE_SPEC — não é bug.

Devolva o relatório no formato exato de specs/findings/TEMPLATE_RELATORIO.md,
em português, e imprima o markdown na tela para eu salvar. Não crie arquivos.

Se não houver achado bloqueante, diga isso com todas as letras. Não invente achado.
~~~

### 4.2 O formato do relatório

É o arquivo `specs/findings/TEMPLATE_RELATORIO.md`:

~~~~markdown
# Relatório de verificação — SPEC-NNNN — AAAA-MM-DD

**Verificador:** <nome do CLI/modelo> · **Commit analisado:** `<sha curto>`
**Spec:** `specs/NNNN-slug.md` · **Diff:** `git diff main...HEAD`

## Veredito

`APROVADO` | `APROVADO COM RESSALVAS` | `REPROVADO`

Uma frase de justificativa.

## Critérios de aceitação

| # | Critério | Situação | Evidência |
|---|---|---|---|
| A1 | | ATENDIDO / NÃO ATENDIDO / NÃO VERIFICÁVEL | comando + saída, ou arquivo:linha |

## Achados

> Um bloco por achado, ordenados por severidade (BLOQUEANTE → ALTA → MÉDIA → BAIXA).

### F1 · <título curto>

- **Severidade:** BLOQUEANTE | ALTA | MÉDIA | BAIXA
- **Tipo:** `BUG_DE_CODIGO` | `LACUNA_DE_SPEC` | `DESVIO_DE_ESCOPO` | `RUIDO`
- **Arquivo:** `caminho/arquivo.ext:linha`
- **Evidência:** trecho de código citado, saída de comando ou linha da spec que foi violada.
- **Por que é problema:** consequência concreta para o usuário ou para o sistema.
- **Correção sugerida:** o menor patch que resolve.
- **Se `LACUNA_DE_SPEC`:** qual seção da spec ficou omissa e que linha deveria existir lá.

## Fora de escopo (visto, não corrigido)

Coisas ruins que existem no repo mas não pertencem a esta spec.
Vira issue, não vira commit agora.

## O que eu NÃO consegui verificar

Seja explícito. "Não rodei em device" é informação; silêncio é armadilha.
~~~~

Cinco campos são obrigatórios em todo achado, e cada um existe por um motivo:

| Campo | Por que é obrigatório |
|---|---|
| **Severidade** | Sem ela você trata nit de estilo com a mesma urgência de vazamento de chave |
| **Tipo** | Decide *onde* a correção acontece: no código ou na spec |
| **Arquivo:linha** | Achado sem endereço é opinião |
| **Evidência** | Impede o verificador de alucinar problema plausível que não existe |
| **Correção sugerida** | Transforma reclamação em próximo passo |

### 4.3 Como salvar o relatório

O verificador imprime; você salva. Assim ele continua sem escrever no repositório:

~~~powershell
Set-Location $Elias
New-Item -ItemType File -Force -Path ".\specs\findings\0001-$(Get-Date -Format 'yyyy-MM-dd').md" | Out-Null
# cole o markdown do relatório no editor e salve
code ".\specs\findings\0001-$(Get-Date -Format 'yyyy-MM-dd').md"
~~~

### 4.4 Exemplo de saída real esperada

Um relatório bom de verdade, na primeira semana, se parece com isto — três achados,
sendo que só um é culpa do código:

~~~text
Veredito: APROVADO COM RESSALVAS
A fatia 1 cumpre A1 e A2, mas dois casos extremos dependem de decisão da spec.

F1 · Cooldown não distingue "sem chave" de "chave rejeitada"
  Severidade: MÉDIA | Tipo: LACUNA_DE_SPEC
  Arquivo: backend_nodejs/services/ttsProvider.js:44
  Evidência: preferredTtsProviderOrder() filtra provedor sem chave e provedor em cooldown
    pelo mesmo caminho; ttsProviderStatus() devolve cooldownUntil:null nos dois casos.
    A seção 5.1 da spec pede "hasKey" e "cooldownUntil" mas não diz como /health/tts
    deve distinguir os dois estados.
  Por que é problema: o diagnóstico de "por que o Elias está mudo" fica ambíguo —
    exatamente o problema que a spec quer resolver.
  Correção sugerida: a spec ganha um campo `state: 'ready'|'no_key'|'cooling_down'`
    na seção 5.2, e só depois o código muda.

F2 · Teste E7 não falha se a implementação quebrar
  Severidade: ALTA | Tipo: BUG_DE_CODIGO
  Arquivo: backend_nodejs/test_tts_failover.js:31
  Evidência: `preferredTtsProviderOrder()` é chamado e o retorno é ignorado;
    não há assert. Removi o corpo da função e o teste continuou passando.
  Correção sugerida: assert.deepStrictEqual(preferredTtsProviderOrder(), []) com os
    dois provedores em cooldown.
~~~

⚠️ Antes de mandar qualquer achado de volta para o escritor, **você** decide o tipo.
O verificador propõe a classificação; a palavra final é sua. É a decisão mais importante
do método e não se delega.

### ✅ Critério de pronto da Fase 4

- Existe um arquivo em `specs/findings/` com data e commit analisado.
- Todo achado tem severidade, tipo, arquivo:linha e evidência.
- Todo critério de aceitação da fatia aparece como ATENDIDO / NÃO ATENDIDO / NÃO VERIFICÁVEL,
  com saída de comando colada.
- Existe a seção "O que eu NÃO consegui verificar" — preenchida, não vazia por preguiça.

---

## Fase 5 — Realimentar a spec

**Por quê.** Sem esta fase o método não é SDD: é code review com passos extras. O que faz a
spec ficar boa é ela absorver, ciclo após ciclo, exatamente as ambiguidades que confundiram
o agente. Uma spec madura é uma lista de mal-entendidos já resolvidos.

### 5.1 A regra de decisão

Para cada achado, uma pergunta só:

> **A spec dizia claramente o que fazer nesse caso?**

    ┌─ SIM, dizia, e o código não cumpriu ──────────▶ BUG DE CÓDIGO
    │                                                 → corrige no mesmo ciclo, sem mexer na spec
    │
    ├─ NÃO dizia, e isso importa para ESTA entrega ─▶ LACUNA DE SPEC
    │                                                 → atualiza a spec PRIMEIRO, código depois
    │
    ├─ NÃO dizia, e é problema real de OUTRA área ──▶ ISSUE
    │                                                 → registra fora, não entra nesta branch
    │
    └─ Não muda nada observável ────────────────────▶ DESCARTA
                                                      → nem issue, nem linha, nem discussão

Duas regras que evitam os erros mais comuns:

- **Lacuna de spec nunca vira commit de código direto.** Atualize a seção 7 (casos extremos)
  ou 5 (contratos) e a seção 10 (registro de mudanças) **antes** de pedir o código. Se você
  corrige só o código, o mesmo mal-entendido volta no próximo ciclo com outra roupa.
- **Issue não entra na branch da spec ativa.** É assim que uma entrega de 3 arquivos vira
  uma de 30.

### 5.2 Os três casos, com exemplo real do Elias

#### Caso 1 — vira linha na spec (`LACUNA_DE_SPEC`)

**Achado:** *"`cartesiaClient.resolveCartesiaVoiceId()` devolve o id do env sem validar. Se a
voz não existir na conta, a API responde 404 e a chamada é contada como falha do provedor —
o Cartesia entra em cooldown de 10 min por um erro de configuração, não de conta."*

**Por quê é lacuna:** a spec fala de erro de **auth/cota** (R7, E1) e de áudio vazio (E5),
mas nunca disse o que é uma "voz inválida". Duas implementações defensáveis: contar como falha
do provedor, ou como erro de configuração sem cooldown. O agente escolheu uma — e não tinha
como saber qual você queria.

**Ação:** a spec ganha, na seção 7:

~~~markdown
| E10 | `CARTESIA_VOICE_ID` aponta para voz inexistente (404) | Trata como erro de configuração, não de conta: uma tentativa com a voz default do código; se falhar de novo, `reason:'cartesia_voice_invalid'` e **sem** cooldown do provedor |
~~~

E a seção 10 registra: *"2026-08-28 — E10 adicionado — achado F3 do verificador, ciclo 2."*
Só então o escritor implementa.

#### Caso 2 — vira issue (fora do escopo)

**Achado:** *"`CLAUDE.md` afirma que o Cartesia é o TTS de Immersion/Shadowing, mas o handler
`shadow_speak` em `server.js:573` usa ElevenLabs, e `@cartesia/cartesia-js` não está em
`package.json`. A única menção a Cartesia no backend é um comentário `@deprecated` em
`audioEncoder.js:262`."*

**Por quê é issue:** o achado é **verdadeiro e importante** — a documentação que orienta os dois
agentes está errada. Mas consertar `CLAUDE.md` não é o que a SPEC-0001 autoriza, e misturar isso
no diff transforma "failover de TTS" em "arrumação geral de documentação".

**Ação:** registre fora da branch e siga:

~~~powershell
"- [ ] CLAUDE.md descreve Cartesia como TTS de Immersion/Shadowing, mas shadow_speak usa ElevenLabs (server.js:573) e o SDK não está instalado. Corrigir a doc ou o código — decidir qual. (achado F4, ciclo 2)" |
  Add-Content -Path .\specs\BACKLOG.md
~~~

⚠️ Revise o `git diff` de `specs/BACKLOG.md` como qualquer outro arquivo antes de commitar.

#### Caso 3 — descarta (`RUIDO`)

**Achado:** *"`ttsProvider.js` usa `console.warn` para o cooldown. Recomendo um logger estruturado
com níveis, como pino ou winston."*

**Por quê descarta:** `console.warn` é o padrão do repositório inteiro — é assim que
`llmClient.js:41` e `elevenLabsClient.js` já fazem. A sugestão adiciona dependência,
diverge do padrão e não muda nada observável para o usuário. Trocar de logger pode ser uma
boa decisão um dia; se for, é uma spec própria, não um achado de rodapé.

**Ação:** nenhuma. Não vira issue, não vira linha, não vira discussão. Anote o descarte no
relatório e siga — a disciplina de descartar é o que mantém o backlog pequeno o bastante
para ser real.

### 5.3 Fechando o ciclo

~~~powershell
Set-Location $Elias
git add specs/
git commit -m "docs(spec): E10 (voz inválida no Cartesia) — achado F3 do ciclo 2"
~~~

Note a ordem: **primeiro o commit da spec, depois o commit do código.** O histórico do git
passa a mostrar que a decisão veio antes da implementação — e daqui a seis meses você
consegue reconstruir *por que* o código é assim.

### ✅ Critério de pronto da Fase 5

- Todo achado do relatório tem um destino explícito: linha na spec, item no backlog, ou descartado.
- A seção 10 da spec registra cada mudança e a origem dela.
- O commit da spec é anterior ao commit do código que a implementa.
- Nenhum achado ficou "pendente" sem decisão — pendência é a forma mais comum de dívida invisível.

---

## Fase 6 — Sessões longas e não supervisionadas

**Por quê.** Deixar um agente rodando três horas sozinho muda a natureza do risco. Numa sessão
curta, você é o guardrail: vê cada passo e interrompe. Numa sessão longa, o guardrail precisa
estar **no ambiente** — porque quando você voltar, o estrago (se houver) já aconteceu.

Só entre nesta fase depois de **cinco ciclos curtos completos**. Antes disso você ainda não sabe
onde o seu agente costuma errar, e guardrail se desenha a partir do erro real, não do imaginado.

### 6.1 Os seis guardrails obrigatórios

#### 1. Branch dedicada, sempre

~~~powershell
Set-Location $Elias
git checkout main
git pull origin main
git checkout -b feat/0001-tts-provider-failover-longa
git status --short          # precisa vir limpo antes de começar
~~~

Árvore suja no início é veneno: quando o diff misturar seu trabalho com o do agente,
você não consegue mais dizer quem escreveu o quê.

#### 2. Proibição de push em `main` — mecânica, não moral

O repositório traz um hook pronto. Instale (uma vez por clone — hooks não são versionados):

~~~powershell
Copy-Item .\scripts\hooks\pre-push .\.git\hooks\pre-push -Force
git push origin main         # precisa FALHAR com a mensagem do guardrail
~~~

Sem o hook, a proibição é só uma frase num arquivo markdown. Com o hook, é uma parede.

#### 3. Infra, migrations e seeds ficam fora

Já estão em `deny` no `.claude/settings.json` (`render.yaml`, `backend_nodejs/seeds/**`,
`local.properties`, `.gitignore`). Some a isso a instrução no prompt da sessão. Rationale:
um erro em código você descobre no teste; um erro em seed ou em variável do Render você
descobre com o app quebrado em produção — e sem teste que avise.

#### 4. O ambiente do agente não tem segredo

Abra o terminal do agente e confirme que as chaves **não** estão lá:

~~~powershell
Get-ChildItem Env: | Where-Object { $_.Name -match "KEY|TOKEN|SECRET|MONGODB|API" }
~~~

A saída precisa vir **vazia**. Se vier alguma coisa, feche o terminal e abra um novo —
não remova variável a variável, é fácil esquecer uma.

Como as tarefas de sessão longa são lógica e teste offline, o agente não precisa de chave
nenhuma. Quando você mesmo for subir o backend para testar de verdade, faça isso em **outro**
terminal, com o `.env` carregado, fora do alcance do agente.

⚠️ Nunca cole valor de chave na conversa com o agente — nem "só para testar", nem mascarado.
Conversa vira log, log vira contexto, contexto vira commit.

#### 5. Limite de arquivos tocados

Escreva o teto no prompt e faça o agente checar sozinho:

~~~text
Limite desta sessão: no máximo 6 arquivos alterados.
Rode `git diff --stat` a cada tarefa concluída. Se passar de 6 arquivos, PARE
e escreva o motivo em specs/findings/sessao-longa-PARADA.md em vez de continuar.
~~~

Seis é um bom número para o Elias: a maior fatia da SPEC-0001 (`ttsProvider` + `cartesiaClient`
+ dois pontos de `server.js` + teste + `package.json`) cabe nele. Diff maior que isso você
não revisa de verdade — passa o olho, que é outra coisa.

#### 6. Ponto de parada automático

O agente precisa saber, sem você, quando o trabalho acabou **ou** deu errado. Cole no prompt:

~~~text
PARE imediatamente e escreva o motivo em specs/findings/sessao-longa-PARADA.md quando
qualquer uma destas acontecer:
- todos os critérios de aceitação da fatia estiverem atendidos (fim feliz);
- `npm run test:unit` falhar duas vezes seguidas pela mesma causa;
- você precisar de um valor de chave, de rede externa ou de acesso ao device;
- você esbarrar numa Questão em Aberto da seção 8 da spec;
- o diff passar de 6 arquivos;
- você concluir que a spec está errada.
Depois de escrever o arquivo de parada, não continue. Aguarde.
~~~

O quinto e o sexto item são os que mais valem. Um agente que decide sozinho que a spec está
errada e "corrige" o rumo produz três horas de trabalho na direção errada.

### 6.2 Checklist pré-sessão

Rode tudo, em ordem. Qualquer item vermelho: não comece.

~~~powershell
Set-Location $Elias
git status --short                                        # 1. árvore limpa
git branch --show-current                                 # 2. NÃO pode ser main
Test-Path .\.git\hooks\pre-push                           # 3. hook instalado
Get-ChildItem Env: | Where-Object { $_.Name -match "KEY|TOKEN|SECRET|MONGODB|API" }  # 4. vazio
Set-Location .\backend_nodejs; npm run test:unit; Set-Location $Elias   # 5. verde ANTES
git log --oneline -1                                      # 6. anote o sha do ponto de partida
~~~

| # | Item | Por que importa |
|---|---|---|
| 1 | Árvore limpa | Separar seu trabalho do trabalho do agente depois |
| 2 | Branch dedicada | `main` continua publicável |
| 3 | Hook instalado | A proibição vira mecânica |
| 4 | Sem segredos no ambiente | O que não existe não vaza |
| 5 | Testes verdes antes | Teste vermelho amanhã é do agente, não herdado |
| 6 | Sha anotado | `git diff <sha>..HEAD` te dá o diff exato da sessão |
| 7 | Spec com fatia e limite escritos | Sem isso o agente inventa o escopo |
| 8 | Ponto de parada colado no prompt | Sem isso ele não sabe terminar |

### 6.3 Checklist de revisão matinal — ⚠️ toda ela é revisão de diff

~~~powershell
Set-Location $Elias
$base = "<sha anotado ontem>"

git log --oneline "$base..HEAD"          # 1. o que aconteceu
git diff --stat "$base..HEAD"            # 2. tamanho e forma
Get-Content .\specs\findings\sessao-longa-PARADA.md -ErrorAction SilentlyContinue   # 3. por que parou
git diff "$base..HEAD"                   # 4. ⚠️ LEIA. Não passe o olho.

Set-Location .\backend_nodejs
npm run test:unit                        # 5. verde na SUA máquina
Set-Location $Elias

git grep -nE "sk_|xi-api-key|api[_-]?key\s*=\s*[""'][A-Za-z0-9]{8}"   # 6. vazio
git diff --stat "$base..HEAD" -- backend_nodejs/audioEncoder.js `
  app/src/main/java/com/roberto/eliasaitutor/audio/ `
  backend_nodejs/seeds/ render.yaml .gitignore                        # 7. vazio
git ls-files | Select-String -Pattern "\.env|local\.properties"       # 8. vazio
~~~

Ordem de leitura do resultado, e o que fazer em cada caso:

| Sinal | Leitura | Ação |
|---|---|---|
| Arquivo de parada existe e diz "critérios atendidos" | Sessão saudável | Siga para a Fase 4 (verificação) |
| Arquivo de parada não existe | O agente não soube terminar | Trate o diff como rascunho, não como entrega |
| Item 7 não veio vazio | Zona de alto risco tocada | ⚠️ Reverta o arquivo e leia por quê antes de qualquer outra coisa |
| Item 6 ou 8 não veio vazio | Possível segredo | Pare tudo. Reverta, e **rotacione a chave** pelo painel do provedor |
| Diff maior que o limite | Limite ignorado | Revise só até onde consegue de verdade; o resto volta para o agente em fatias |

⚠️ A revisão matinal é a **única** barreira entre uma sessão longa e a sua `main`.
Se num dia você não tiver tempo de ler o diff inteiro, não commite nesse dia. A branch espera.

### ✅ Critério de pronto da Fase 6

- `git push origin main` falha com a mensagem do guardrail.
- O terminal do agente não tem nenhuma variável de ambiente com chave.
- Existe um `specs/findings/sessao-longa-PARADA.md` explicando por que a sessão terminou.
- O diff da sessão foi lido linha a linha antes de qualquer commit em `main`.

---

## Métricas

Cinco números. Anote-os em `specs/METRICAS.md` ao fim de cada ciclo — dois minutos por ciclo.
Sozinhos eles dizem pouco; a **tendência** ao longo de duas semanas diz tudo.

| # | Indicador | Como medir | O que você quer ver |
|---|---|---|---|
| M1 | **% de achados que eram lacuna de spec** | `LACUNA_DE_SPEC ÷ total de achados` | Começa alto (60–80% é normal e saudável) e **cai** ao longo das semanas. Não cai? Você está corrigindo código sem realimentar a spec — a Fase 5 não está acontecendo. |
| M2 | **Retrabalho por ciclo** | nº de arquivos reescritos no ciclo seguinte ÷ arquivos entregues | Abaixo de 20%. Acima disso, suas fatias estão grandes demais. |
| M3 | **Tempo até o primeiro desvio improdutivo** | minutos entre o start do agente e a primeira ação fora do escopo | Precisa **subir**. Se são sempre 8 minutos, o problema é a seção 2.1 da spec, não o modelo. |
| M4 | **Achados bloqueantes que escaparam para a `main`** | bugs encontrados no device ÷ ciclos | Tende a zero. É a métrica de confiança: enquanto não for zero por 3 ciclos seguidos, não faça sessão longa. |
| M5 | **Ciclos por semana** | contagem | 3 a 5. Menos que 3, o método não pegou. Mais que 8 com 1 h/dia, suas specs viraram tarefas — e você voltou a programar por prompt. |
| M6 | **Tempo de revisão de diff por ciclo** | minutos de leitura | 10 a 25 min. Menos que 10 significa que você não está lendo; mais que 25, a fatia está grande. |

Uma leitura combinada que vale ouro: **M1 caindo e M3 subindo** = a spec está aprendendo.
É exatamente esse o objetivo do método. Se M1 cai mas M4 sobe, você está escrevendo specs
mais permissivas em vez de mais claras.

---

## Erros comuns nas primeiras semanas

1. **Spec grande demais.** "Implementar o failover de TTS inteiro" em vez de "criar
   `ttsProvider.js` e seu teste". Sintoma: o diff não cabe na revisão e você aprova no olho.
   *Correção:* se a spec tem mais de 8 critérios de aceitação, quebre.

2. **Não-escopo vago.** "Não mexa em nada de áudio" não é executável. `audioEncoder.js`,
   `OpusAudioPlayer.kt`, `JitterBuffer.kt` é.
   *Correção:* a seção 2.1 nomeia arquivos e caminhos.

3. **Deixar o verificador consertar.** É a tentação mais forte — ele já achou, já sabe o
   conserto. No minuto em que ele edita, você perde a opinião independente e ganha um segundo
   escritor com metade do contexto.
   *Correção:* worktree separada + modo somente-leitura. Camada mecânica, não força de vontade.

4. **Tratar lacuna de spec como bug.** É o erro que esteriliza o método: você corrige o código,
   a spec continua omissa, e o mesmo mal-entendido volta em outra roupa no ciclo seguinte.
   *Correção:* M1 é justamente o alarme disso. Se ela não cai, é aqui.

5. **Aceitar diff sem ler.** Depois de três ciclos bons vem a confiança, e com ela o
   "está tudo certo, ele acertou os outros".
   *Correção:* ⚠️ `git diff` completo, sempre. É inegociável, e é a razão de este guia marcar
   cada ponto de revisão.

6. **Usar o `AGENTS.md` desatualizado como contexto.** Ele diz Flutter/FastAPI. Um agente que
   lê isso vai propor solução para um projeto que não existe.
   *Correção:* o aviso no topo, da Fase 0.

7. **Rodar sessão longa cedo demais.** Antes de 5 ciclos curtos você ainda não sabe onde o seu
   agente derrapa — e guardrail se desenha a partir do erro observado.
   *Correção:* M4 = 0 por três ciclos seguidos é o portão de entrada da Fase 6.

8. **Backlog virando lixeira.** Todo achado fora de escopo virando issue, nenhuma sendo fechada.
   Em duas semanas o `BACKLOG.md` tem 40 linhas e ninguém lê.
   *Correção:* descarte é uma decisão legítima. Se um item passa 14 dias sem virar spec, apague —
   se for importante mesmo, ele volta sozinho.

9. **Spec escrita depois do código.** "Deixa eu implementar rápido e documento depois."
   O resultado é uma spec que descreve o que existe, e uma spec descritiva não protege escopo nenhum.
   *Correção:* commit da spec **antes** do commit do código. O `git log` te delata.

10. **Chave em conversa "só para testar".** O caminho mais curto entre você e um segredo vazado.
    *Correção:* painel de ambiente, sempre. Se um agente pedir uma chave, a resposta é sempre não.

---

## Plano de 14 dias

Ritmo de **1 hora por dia**. Cada dia tem um entregável verificável — se não deu, repita o dia,
não pule.

### Semana 1 — o loop girando

| Dia | Foco | Entregável | Tempo |
|---|---|---|---|
| **1** | Fase 0 completa | `specs/`, `VERIFIER.md`, `.claude/settings.json`, aviso no `AGENTS.md`, `.env` no `.gitignore`. Critério de pronto da Fase 0 passando. | 45 min |
| **2** | Ler a SPEC-0001 inteira e **discordar dela** | Pelo menos 3 edições suas na spec (uma decisão, um caso extremo, um critério). Se você não mudou nada, não leu de verdade. | 40 min |
| **3** | Fase 2 — os dois agentes | Worktree criada; verificador **recusa** criar `teste.txt`; escritor abre na branch da spec. | 50 min |
| **4** | ⚠️ Ciclo curto 1 (Fase 3) | `ttsProvider.js` + `test_tts_failover.js` + `npm run test:unit` verde. Um commit seu. | 60 min |
| **5** | Fase 4 — primeira verificação | Relatório salvo em `specs/findings/`. Cada achado com tipo e evidência. | 45 min |
| **6** | Fase 5 — primeira realimentação | Spec atualizada (seções 7 e 10) + `BACKLOG.md` criado. Commit da spec **antes** do de código. | 40 min |
| **7** | Métricas e pausa | `specs/METRICAS.md` com M1–M6 do ciclo 1. Anote em uma frase o que te surpreendeu. | 20 min |

### Semana 2 — repetição e primeira sessão longa

| Dia | Foco | Entregável | Tempo |
|---|---|---|---|
| **8** | ⚠️ Ciclo curto 2 — `cartesiaClient.js` | Cliente + teste offline (sem rede, resposta mockada). Verificação no mesmo dia. | 60 min |
| **9** | ⚠️ Ciclo curto 3 — `server.js`, chat principal | Failover ativo no `handleAIResponse`; `tts_status` com `provider` e `fallback`. | 60 min |
| **10** | ⚠️ Ciclo curto 4 — `shadow_speak` + `/health/tts` | E9 atendido sem duplicar lógica; rota respondendo sem segredo (critério A3). | 60 min |
| **11** | Teste no device | Com `ELEVENLABS_API_KEY` inválida de propósito, o Elias **fala** (A4). Barge-in continua cortando (A8). Anote M4. | 45 min |
| **12** | ⚠️ Ciclo curto 5 — Android | `EliasViewModel` deixa de tentar fallback local quando `reason` é de auth. Toast por taxonomia. | 60 min |
| **13** | Preparar a sessão longa (Fase 6) | Hook instalado e testado (`git push origin main` falha), checklist pré-sessão passando, prompt com fatia + limite de 6 arquivos + ponto de parada. | 40 min |
| **14** | ⚠️ Primeira sessão longa + revisão matinal | Sessão de 2–3 h com o escritor; `sessao-longa-PARADA.md` escrito; checklist matinal inteira executada; diff lido linha a linha. | 30 min de setup + 40 min de revisão |

**Portão do dia 13:** só siga para a sessão longa se M4 (bloqueantes que escaparam) for **zero**
nos ciclos 3, 4 e 5. Não sendo, repita a Semana 2 com fatias menores. Sessão longa com método
imaturo não acelera nada — só produz mais código para você revisar no mesmo tempo.

---

## Resumo — os comandos que você vai usar todo dia

~~~powershell
$Elias = "$HOME\StudioProjects\EliasAITutor2"; Set-Location $Elias

# começar um ciclo
git checkout main; git pull origin main
git checkout -b feat/0001-tts-provider-failover
claude

# ⚠️ revisar antes de aprovar
git diff --stat; git diff
Set-Location .\backend_nodejs; npm run test:unit; Set-Location $Elias
git grep -nE "sk_|xi-api-key|api[_-]?key\s*=\s*[""'][A-Za-z0-9]{8}"

# verificar
git worktree add ..\elias-verify (git branch --show-current)
# … roda o verificador lá dentro, salva o relatório em specs/findings/
git worktree remove ..\elias-verify

# fechar
git add specs/; git commit -m "docs(spec): <mudança> — achado <F#> do ciclo <n>"
git add <arquivos de código>; git commit -m "feat(tts): <mudança> (SPEC-0001)"
git push -u origin (git branch --show-current)
~~~

Uma última coisa, que é a única que realmente importa: **a spec é sua, o código é do agente.**
No dia em que você aceitar um diff sem ler, os papéis se invertem — e aí não existe mais SDD,
existe só um repositório que você não conhece mais.
