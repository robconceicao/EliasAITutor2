# ADR-0001 — Failover de TTS acontece por provedor, não por voz

- **Data:** 2026-08-26
- **Status:** proposta
- **Spec:** `specs/0001-tts-provider-failover.md`

## Contexto

A cadeia de fallback de voz do Elias é inteiramente interna à ElevenLabs:
voz principal → voz reserva (`openTtsWebSocketWithFallback`) → REST completo
(`synthesizePcmRest`) → texto puro. O fallback "de emergência" do Android
(`network/ElevenLabsApi.kt`) também é ElevenLabs.

Os três degraus compartilham **uma única chave**. Em 2026-08-26 a chave passou a
responder `400 authentication_error` e o app ficou mudo: a cadeia inteira caiu junto,
porque ela protege contra falha de *voz*, não contra falha de *conta*.

## Decisão

Introduzir um degrau acima da cadeia atual: seleção de **provedor**
(`services/ttsProvider.js`), com ordem `elevenlabs → cartesia → texto`.
Erro de autenticação ou cota coloca o provedor em cooldown por 10 min,
espelhando `markClaudeUnavailable()` do `llmClient.js`.

O Cartesia entra por síntese completa (REST), reaproveitando o caminho
`emitRestTtsAsOpus()` que já existe e já é testado.

## Alternativas descartadas

1. **Adicionar mais vozes de reserva na ElevenLabs.** Não resolve chave morta —
   é a falha que aconteceu de verdade.
2. **Trocar de provedor definitivamente.** A qualidade da ElevenLabs no caminho feliz
   é a razão da escolha original; o problema é ausência de rede de segurança, não qualidade.
3. **Fazer o fallback no Android.** O app teria que carregar uma segunda chave no
   `BuildConfig` — mais superfície de vazamento e nenhuma forma de rotacionar sem publicar
   versão nova.
4. **Streaming do Cartesia já nesta entrega.** Dobra o tamanho da mudança e toca no
   jitter buffer, que a spec proíbe mexer.

## Consequências

**Boas**
- O app deixa de ter um ponto único de falha de voz.
- `/health/tts` passa a dizer, sem expor segredo, por que o Elias emudeceu.
- O padrão de cooldown fica igual ao do LLM: um jeito só de ler o sistema.

**Ruins / custos**
- Segunda conta e segunda fatura para manter.
- Timbre muda quando o fallback entra — o usuário percebe. Aceito: voz diferente é
  melhor que silêncio.
- Cooldown em memória some no restart (Render free dorme). Aceito nesta fase.

## Revisitar quando

- O fallback for acionado mais de 2× por semana → o problema é a conta primária, não a rede de segurança.
- A latência do REST do Cartesia passar de 4 s com frequência → implementar streaming (SPEC-0002).
