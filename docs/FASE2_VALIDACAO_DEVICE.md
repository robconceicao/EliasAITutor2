# Fase 2 — Checklist de validação no aparelho real

> **Atualização:** o checklist completo de Elias v2 (Fases 0–5) está em  
> [`ELIAS_V2_REGRESSAO_DEVICE.md`](./ELIAS_V2_REGRESSAO_DEVICE.md).  
> Este arquivo permanece como histórico da Fase 2.

**Objetivo:** confirmar Tutor Adaptativo (quiz, checkpoint, `held_back`, calendário pausável) e hardenings de voz (descarte de áudio atrasado) **antes** da Fase 3.

Pré-requisito: backend reiniciado com log `Quiz seed … 26 weeks`.

---

## A. Watchdog / voz (rápido)

| # | Ação | Esperado |
|---|---|---|
| A1 | Chat normal com rede ok | Voz Liam (ou MAIN) toca; sem spinner eterno |
| A2 | Se aparecer toast "Tentando voz alternativa…" | Fallback em curso; texto pode continuar |
| A3 | Se cair em modo texto | Toast "Voz indisponível…"; **texto** da resposta permanece; **sem** áudio fantasma sobreposto depois |
| A4 | Barge-in durante fala | Microfone não trava; stream cancela |

**Notas de produto (não bloqueantes):**
- Pior caso até text-only ≈ **21s** (8 + 5 + 8).
- Mensagens progressivas: backend emite `tts_status` (`Tentando voz alternativa…`); UI mostra toast leve. Polimento visual mais rico = item futuro.
- Áudio atrasado da voz principal é **descartado** por `ttsStreamGen` + `hooks.isActive` no backend e `stopPlayout()` no Android ao falhar/trocar.

---

## B. Quiz semanal

| # | Ação | Esperado |
|---|---|---|
| B1 | Home → **Quiz semanal** | 10 questões da **semana atual**; sem `correct_index` visível antes de enviar |
| B2 | Responder tudo e enviar | Score %, passed/failed, mínimo 70% |
| B3 | Rede off no meio do load | Erro + **Tentar novamente** (não "Carregando…" eterno) |

---

## C. Checkpoint + held_back (calendário)

| # | Ação | Esperado |
|---|---|---|
| C1 | **Checkpoint** sem quiz / sem prática suficiente | `ready: false`, reasons claras |
| C2 | Após reprovar | Home em **Modo revisão**; `held_back=true` |
| C3 | Aguardar virada de dia (ou simular no backend `total_paused_days`) | Semana **não** avança só por data civil; `total_paused_days` sobe 1x/dia enquanto `held_back` |
| C4 | Aprovar checkpoint depois de quiz ok + CEFR + poucos críticos | `held_back=false`; calendário retoma com dias pausados contabilizados |

**CEFR:** comparação ordinal explícita `A1 < A2 < B1 < B2 < C1 < C2` em `evaluateReadiness.js` (`CEFR_RANK`) — **não** comparação de string bruta.

---

## D. Modo revisão = prática completa (não só quiz)

Ordem na home quando `held_back`:

1. **Conversa temática (revisão)** — mesma semana / tópicos  
2. **Chunks da semana** — IPA / shadowing  
3. **Quiz semanal (após a prática)**  
4. **Checkpoint de prontidão**

| # | Ação | Esperado |
|---|---|---|
| D1 | Passo 1 | Abre chat PROGRAM, nível = `program_weeks.level`, **sem** seletor de nível |
| D2 | Passo 2 | Drill de chunks da semana atual |
| D3 | Passo 3 | Quiz reaplicado (não é o único botão da revisão) |
| D4 | Passo 4 | Checkpoint usa quiz + feedbacks da semana |

---

## E. Persistência

| # | Ação | Esperado |
|---|---|---|
| E1 | Forçar `held_back` → matar app → reabrir | Card de revisão ainda visível (state do backend + cache) |
| E2 | Offline com cache | Banner + Tentar novamente; semana/cache locais coerentes |

---

## Sinais de falha (parar e reportar)

- Spinner eterno em quiz/checkpoint/home  
- Semana avança com `held_back=true` sem incrementar pause  
- Modo revisão só oferece quiz, sem conversa/chunks  
- Áudio da voz principal tocando **depois** do fallback  
- Pergunta de nível no fluxo PROGRAM  
