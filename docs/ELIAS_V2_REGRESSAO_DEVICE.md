# Elias v2 — Checklist único de regressão no aparelho

**Branch sugerida:** `feat/task-final-tts-echo-complete`  
**Pré-requisito:** reiniciar backend e confirmar logs:

```
📚 Curriculum seed … 26 weeks
📝 Quiz seed … 26 weeks
[boot] TTS main=… elevenLabsKey=true
```

Marque cada item no aparelho real. Tempo estimado: **25–40 min**.

---

## 0. Boot e conectividade

| # | Ação | Esperado | OK? |
|---|---|---|---|
| 0.1 | Abrir app com backend no ar | Sem banner Offline eterno |
| 0.2 | Aba Programa carrega | Hero + semana; máx. ~10s; se falhar → **Tentar novamente** |
| 0.3 | Matar app e reabrir | Estado do programa persiste (semana / held_back se houver) |

---

## 1. Voz e streaming (Fase 1 / A.2 / A.5)

| # | Ação | Esperado | OK? |
|---|---|---|---|
| 1.1 | Chat → falar ou texto | Elias responde com **voz** (Liam/MAIN) + texto |
| 1.2 | Barge-in (falar por cima) | Voz para; microfone não trava |
| 1.3 | Spinner / “Elias não fala” | Nunca gira sem fim; timeout → texto ou fallback |
| 1.4 | Toast “Tentando voz alternativa…” (se aparecer) | Sem áudio fantasma da voz anterior por cima |
| 1.5 | Rede ruim / desligar Wi‑Fi no meio | Erro/texto; **Tentar novamente** visível |

**Pior caso documentado até text-only:** ~21s (8 + 5 + 8).

---

## 2. Fluxos PROGRAM vs FREE (A.1)

| # | Ação | Esperado | OK? |
|---|---|---|---|
| 2.1 | Programa → Iniciar sessão | Chat PROGRAM; **sem** chips Beginner/Intermediate/Advanced |
| 2.2 | Banner PROGRAM | Nível = CEFR da semana (ex. B1), não gamificação |
| 2.3 | Aba Chat sem sessão ativa | Fluxo FREE com seletor de nível ok |

---

## 3. Tutor adaptativo (Fase 2)

| # | Ação | Esperado | OK? |
|---|---|---|---|
| 3.1 | **Quiz semanal** | 10 questões; envio → score % e passed/failed (70%) |
| 3.2 | **Checkpoint** sem pré-requisitos | `ready: false` + reasons |
| 3.3 | Após reprovar | Card **Modo revisão** com passos 1–4 |
| 3.4 | Passo 1 conversa | Sessão temática da **mesma semana** |
| 3.5 | Passo 2 chunks | Drill IPA da semana |
| 3.6 | Passo 3 quiz de novo | Reaplica quiz (não é o único botão da revisão) |
| 3.7 | Passo 4 checkpoint | Pode limpar `held_back` se sinais ok |
| 3.8 | Com `held_back`, virada de dia | Semana **não** “pula” só por calendário; `total_paused_days` sobe |

---

## 4. Tradução contextual (Fase 3 / A.3)

| # | Ação | Esperado | OK? |
|---|---|---|---|
| 4.1 | Toque 🇧🇷 Traduzir | PT **abaixo** do inglês; original intacto |
| 4.2 | Dizer “não entendi” / “traduz pra mim” | Traduz última mensagem do Elias |
| 4.3 | Só “traduz” (frase pura) | Traduz sem criar bolha de usuário desnecessária |
| 4.4 | Timeout / offline | Erro + **Tentar de novo** |

---

## 5. UI Fase 4–5

| # | Ação | Esperado | OK? |
|---|---|---|---|
| 5.1 | Home Programa | Hero, chips semana/nível, barra jornada |
| 5.2 | Echo Mode | Card frase + card **IPA** grande; foco do dia |
| 5.3 | Immersion | Hero “IMMERSION”; ouvir → emoji funciona |
| 5.4 | Progress (aba) | Streak + card programa (semana / dias pausados / revisão) |
| 5.5 | Config (engrenagem) | Tutor adaptativo (held_back, dias pausados) somente leitura |

---

## 6. Immersion / Shadowing áudio

| # | Ação | Esperado | OK? |
|---|---|---|---|
| 6.1 | Immersion ouvir frase | Áudio do backend (não silêncio eterno) |
| 6.2 | Echo: Ouça → Grave → Compare | Score/feedback; IPA visível |

---

## Sinais de falha (parar e reportar)

- Spinner eterno em qualquer tela  
- Semana avança com `held_back=true` sem pause  
- Áudio da voz principal **depois** do fallback  
- PROGRAM pergunta nível  
- Quiz sem seed / 404  
- Tradução apaga o inglês  

---

## Notas de polimento futuro (não bloqueiam)

- UI mais rica para `tts_status` (banner em vez de só toast)  
- Wizard linear forçado da rodada de revisão (hoje: passos 1–4 visíveis, ordem orientada)  
- Ajuste fino de cores light/dark entre Chat e resto do app  

---

## Resultado

| Data | Aparelho | Resultado | Observações |
|---|---|---|---|
| | | ☐ OK · ☐ Falhas (listar) | |
