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
