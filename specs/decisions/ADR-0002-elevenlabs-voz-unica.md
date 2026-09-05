# ADR-0002 — ElevenLabs é a única voz do Elias

- **Data:** 2026-08-26
- **Status:** aceita — **reverte a ADR-0001**
- **Specs:** revoga `specs/0001-tts-provider-failover.md`; abre `specs/0002-silencio-diagnosticavel.md`

## Contexto

A ADR-0001 respondeu ao app mudo com um segundo provedor de TTS. Dois ciclos depois, o app **continua
mudo** — e o failover nunca chegou a ser a causa nem a cura, porque a integração no `server.js` não
foi escrita. O silêncio persiste pelo motivo original: a ElevenLabs recusa a chave
(`400 authentication_error`).

Isso expõe o erro de leitura da ADR-0001: ela tratou uma falha de **conta** como um problema de
**arquitetura**. Redundância de provedor não conserta credencial morta — só a esconde atrás de uma
voz diferente, e a um custo permanente.

## Decisão

A ElevenLabs é a única voz do Elias. Sem segundo provedor.

O esforço muda de alvo: em vez de substituir a voz quando ela falha, tornar a falha **imediatamente
diagnosticável** — que o `/health` diga se a chave *funciona*, não apenas se *existe*, e que o app
diga ao usuário o que houve.

## Consequências

**Boas**
- Uma conta, uma fatura, um contrato de rede — e a Q4 por verificar deixa de existir.
- O timbre nunca muda no meio da aula.
- Menos código: sai `cartesiaClient.js`, sai a seleção de provedor, sai o cooldown.

**Ruins / custos**
- Volta o ponto único de falha: chave morta = Elias mudo. Aceito conscientemente — a mitigação passa
  a ser diagnóstico rápido, não redundância.
- Dois ciclos de trabalho descartados. Não foram perdidos: a classificação de erro de auth/cota e a
  taxonomia de `reason` sobrevivem, e o método mostrou o custo real da direção antes de ela chegar à
  produção.

**Removido explicitamente**
- O cooldown de 10 min. Com um provedor só, pôr a ElevenLabs de castigo garante silêncio pelo período
  inteiro — inclusive depois de a chave ser corrigida no painel. Com dois provedores era economia de
  latência; com um, é sabotagem.

## Revisitar quando

- A chave morrer mais de uma vez por trimestre sem aviso — aí o problema é operacional e merece
  alarme, não um segundo provedor.
- Houver orçamento e vontade de manter duas contas de verdade.
