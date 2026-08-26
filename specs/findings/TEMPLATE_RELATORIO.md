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
