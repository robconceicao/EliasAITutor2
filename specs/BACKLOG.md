# Backlog — achados fora do escopo da spec ativa

Item aqui **não** entra na branch da spec em andamento. Ele espera virar spec própria.

Regra de higiene: item parado há mais de 14 dias sem virar spec é apagado.
Se for importante de verdade, ele volta sozinho no próximo ciclo.

- [ ] `CLAUDE.md` descreve Cartesia como TTS de Immersion/Shadowing, mas `shadow_speak`
      (`backend_nodejs/server.js:573`) usa ElevenLabs e `@cartesia/cartesia-js` não está em
      `package.json` — a única menção no backend é um `@deprecated` em `audioEncoder.js:262`.
      Decidir se a doc está errada ou o código regrediu. (origem: leitura do repo, 2026-08-26)
