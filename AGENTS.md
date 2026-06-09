# Projeto: Elias AI Tutor

## Stack
- Frontend: Android Nativo (Kotlin & Jetpack Compose)
- Backend: Node.js (Express & Socket.io)
- PostgreSQL (via Supabase)

---

## Objetivo do Projeto

Aplicativo tutor inteligente com IA focado em:
- ensino personalizado
- conversação
- análise de desempenho
- organização modular
- experiência fluida para estudantes

---

## Regras obrigatórias

- Nunca alterar arquivos .env
- Nunca fazer deploy automaticamente (exceto via commit aprovado no Github)
- Nunca remover arquivos sem confirmação
- Sempre explicar alterações importantes
- Sempre revisar imports
- Sempre criar código modular
- Sempre priorizar legibilidade
- Sempre validar erros
- Nunca quebrar rotas e eventos de socket existentes

---

## Estrutura do Projeto

- /app = Frontend Android App
- /backend_nodejs = Servidor Node.js
- /build = artefatos de compilação

---

## Padrões de Código

- Código limpo
- Funções pequenas
- Nomes descritivos
- Separação de responsabilidades
- Modularização obrigatória
- Evitar duplicação
- Tratamento de erros obrigatório

---

## Flutter

- Criar widgets reutilizáveis
- Evitar widgets gigantes
- Separar UI da lógica
- Manter consistência visual
- Priorizar performance mobile

---

## Backend

- APIs organizadas
- Services separados de routers
- Validar entradas
- Logs claros
- Não colocar lógica pesada nas rotas

---

## Segurança

- Nunca acessar secrets
- Nunca expor tokens
- Nunca modificar credenciais
- Nunca executar comandos destrutivos

---

## Testes

- Criar testes para lógica crítica
- Validar edge cases
- Garantir estabilidade antes de refatorações