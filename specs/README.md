# /specs — a fonte da verdade antes do código

Nesta pasta a decisão vem antes da implementação. O código é consequência da spec;
quando os dois discordam, um dos dois está errado — e descobrir **qual** é o trabalho
do verificador.

## Estrutura

| Caminho | O que é |
|---|---|
| `TEMPLATE_SPEC.md` | Modelo em branco. Copie para começar uma spec nova. |
| `NNNN-slug.md` | Uma entrega. Numeração sequencial, nunca reaproveitada. |
| `decisions/ADR-NNNN-*.md` | Decisão de arquitetura com alternativas descartadas e consequências. |
| `findings/` | Relatórios do agente verificador, um por ciclo. |
| `findings/TEMPLATE_RELATORIO.md` | Formato obrigatório do relatório. |

## Regras

1. Uma spec, uma branch, um objetivo em uma frase.
2. Spec sem **critério de aceitação verificável** não sai de `rascunho`.
3. Achado do verificador não vira commit direto: primeiro decide-se se é
   bug de código (corrige) ou lacuna de spec (a spec muda **antes** do código).
4. Nenhum valor de chave, token ou senha entra em spec, ADR ou relatório. Só nomes de variável.
5. Spec entregue não é apagada — vira histórico. Se foi abandonada, marque `revogada` e diga por quê.

## Ciclo

    spec → escritor implementa → verificador audita → spec é atualizada → próximo ciclo

Guia completo do método: `docs/SDD_GUIA_AGENTES.md`.
