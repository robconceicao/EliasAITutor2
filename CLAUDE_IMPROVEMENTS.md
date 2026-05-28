# Elias AI Tutor - Relatório de Arquitetura e Melhorias para Análise do Claude 🤖💬

Este documento foi gerado automaticamente para resumir a arquitetura atualizada, as correções de bugs realizadas recentemente e servir de insumo para que o Claude analise o projeto e sugira refinamentos avançados.

---

## 1. Visão Geral do Sistema (Arquitetura Atual)

O aplicativo **Elias AI Tutor** é uma plataforma gamificada de ensino de inglês que utiliza Inteligência Artificial em tempo real. A arquitetura foi recentemente otimizada para nuvem utilizando o seguinte fluxo:

```
[Android App] <--- (WebSockets / Socket.io) ---> [Node.js Backend]
     |                                                 |
     | (STT & Barge-in local)                          |---> [LLM Stream] (Claude 3.5 / Gemini / DeepSeek)
     | (PCM Audio Track Playback)                      |---> [TTS WebSockets] (Cartesia AI Audio chunks)
     |
     v
[Database Sync] (Supabase Client nativo no Android)
```

### Componentes Principais do App Android (Kotlin + Jetpack Compose)
1. **`SocketClient.kt`**: Novo cliente centralizador de WebSocket (Socket.io) que conecta o app ao backend em nuvem. Trata os eventos `audio_chunk` (recebe PCM Float 32 em Base64), `texto_chunk` (streaming de texto), `estado_ia` ("falando" ou "ociosa") e `mensagem_ia` (objeto final com erros gramaticais e sentimentos).
2. **`AudioEngine.kt`**: Gerencia a gravação de voz com `SpeechRecognizer` (STT local com cancelamento de eco `AcousticEchoCanceler`) e reprodução em tempo real utilizando `AudioTrack` em modo stream.
3. **`EliasViewModel.kt`**: Coordena os dados e o fluxo de voz. Traduz estados para a UI, inicializa a sessão com ID do usuário e dispara o comando de interrupção (Barge-in) via socket (`usuario_interrompeu`) assim que o usuário começa a falar, limpando os buffers de áudio locais.
4. **`OndasSonorasAgente.kt`**: UI em Jetpack Compose com 7 barras animadas e gradientes neon reativos aos estados (Vermelho/Laranja = Usuário falando; Teal/Cyan = Elias falando; Azul pulsante = Elias pensando).

### Componentes do Backend (Node.js + Socket.io)
- **`server.js`**: Recebe a conexão via socket, gerencia histórico de conversação em memória (ou MongoDB) e consome as APIs de LLM e TTS.
- **Roteamento de IAs (Multi-LLM)**: Suporta **Claude 3.5 Sonnet**, **Gemini 1.5/2.5 Flash** e **DeepSeek Chat** de forma nativa com **failover automático** (caso uma chave expire ou falhe, ele chaveia para outra sem interromper o usuário).
- **Streaming Bidirecional**: Pede a resposta em streaming ao LLM, filtra as tags de resposta (`<RESPONSE>`) em tempo real no Node.js e envia os pedaços de texto imediatamente para o WebSocket da **Cartesia**, gerando áudio instantâneo enviado via Socket.io ao celular.

---

## 2. Ajustes e Correções Recentes (Bugs Resolvidos)

Fizemos uma varredura no código e corrigimos inconsistências que poderiam degradar a experiência e a performance do dispositivo do estudante:

### A. Correção de Vazamento de Memória e Hardware no ViewModel
- **Inconsistência**: O `EliasViewModel` instanciava o `AudioEngine` e abria conexões persistentes de socket, mas não liberava nada no descarte (re-criação da Activity, fechamento do app ou rotação de tela).
- **Solução**: Sobrescrevemos o método `onCleared()` no `EliasViewModel.kt` para liberar explicitamente os recursos do microfone/audio track (`audioEngine.release()`) e fechar todas as conexões de socket de forma limpa.

### B. Correção de Vazamento de Gravação no Shadowing
- **Inconsistência**: Na tela de Shadowing (`ShadowingScreen.kt`), se o usuário saísse da tela ou mudasse de aba no meio de uma gravação, o `MediaRecorder` continuava ativo em segundo plano gravando infinitamente, travando o microfone.
- **Solução**: Implementamos um bloco `DisposableEffect(Unit)` no Compose para parar e liberar o `MediaRecorder` de forma segura assim que a tela sai da árvore de renderização.

### C. Compatibilidade com Android 12+ (API 31+)
- **Inconsistência**: O construtor deprecado `MediaRecorder()` estava sendo usado diretamente, gerando alertas de compilação e instabilidade em versões recentes do Android.
- **Solução**: Atualizamos para utilizar `MediaRecorder(context)` em dispositivos rodando Android 12 ou superior, mantendo compatibilidade com versões anteriores.

---

## 3. Sugestões de Melhorias para Análise do Claude

*Copie o prompt abaixo e envie para o Claude para receber sugestões de código refinadas para as próximas etapas do projeto.*

***

### 📋 COPIE O PROMPT ABAIXO:

```markdown
Olá, Claude! Estou desenvolvendo o "Elias AI Tutor", um aplicativo tutor inteligente de inglês para Android (Kotlin/Compose) integrado a um backend em Node.js (Socket.io). 

O sistema orquestra:
- Streaming de áudio ultra-rápido usando Cartesia AI (PCM Float 32 de 44.1kHz).
- Orquestração de LLMs (Claude 3.5, Gemini, DeepSeek) no backend com failover.
- Barge-in (interrupção da fala da IA) baseado no onBeginningOfSpeech do SpeechRecognizer nativo no Android.
- Gravação/Reprodução local usando AudioTrack com AcousticEchoCanceler habilitado.

Aqui está um resumo das correções feitas recentemente no Android:
1. Implementamos onCleared() no ViewModel para interromper conexões de socket e rodar audioEngine.release() (desalocando AudioTrack, SpeechRecognizer e EchoCanceler).
2. Adicionamos DisposableEffect(Unit) na tela de Shadowing para liberar o MediaRecorder caso o usuário saia da tela durante a gravação.
3. Tratamos a instanciação do MediaRecorder para usar o construtor correto no Android 12+.

Gostaria que você analisasse essa arquitetura e me sugerisse melhorias de código detalhadas focando em:

1. **Redução de Banda e Latência de Rede (Codec de Áudio)**: Atualmente, estamos transmitindo chunks de áudio PCM Float 32 brutos convertidos em Base64 através de sockets. Isso gasta muita banda. Qual seria o melhor codec compactado (ex: Opus, AAC) para decodificar nativamente no Kotlin de forma simples, e como seria o código de conversão no Node.js e no Android?
2. **Robustez no WebSocket (Reconexão)**: Como melhorar o SocketClient.kt para que ele lide de forma resiliente com perdas de Wi-Fi ou mudança para redes móveis 4G/5G no meio da conversação, sem perder o histórico do chat ou crashar?
3. **Detecção de Silêncio Avançada (VAD - Voice Activity Detection)**: O SpeechRecognizer do Android depende do Google Speech Services e às vezes tem delay para disparar o "onResults". Como poderíamos implementar uma detecção de silêncio mais rápida e local (baseada no RMS/volume ou uma biblioteca leve de VAD) para enviar o áudio assim que o usuário para de falar?
4. **Outras Melhorias na UI/UX**: Ideias de animação ou controle de estado Compose para tornar a conversação de voz ainda mais premium (ex: efeitos de respiração nas ondas sonoras, detecção visual de volume do microfone nas ondas).

Me forneça explicações conceituais e os blocos de código (Kotlin e Node.js) correspondentes para implementarmos.
```
