# 0003 — Backend remoto no APK de homologação

Status: implementação na branch `codex/homologation-license-ledger`.

## Objetivo

Completar o pedido de homologação sem licença comercial com um APK que use o backend remoto existente quando instalado em um aparelho de teste.

## Evidência

O APK do CI anterior continha `http://10.0.2.2:3000`: sem `BACKEND_URL`, o workflow de homologação usava o default de desenvolvimento do Gradle. O workflow de release e `docs/DEPLOY_RENDER.md` já documentam `https://eliasaitutor2.onrender.com` como endereço existente.

## Escopo e critérios de aceitação

1. O workflow de homologação preserva uma `BACKEND_URL` configurada e usa o endereço documentado quando ausente.
2. Um teste da variante homologation verifica a URL compilada no CI: HTTPS, host remoto e igualdade com a configuração do job. O desenvolvimento local pode continuar usando emulador.
3. O bypass continua restrito à variante de homologação; a variante release continua compilando false, validada pelo teste existente.
4. Gerar novo APK no CI e verificar a URL no arquivo entregue, assinatura e SHA-256.

## Não escopo

Modificar áudio, TTS, eventos Socket.io, credenciais, licenciamento oficial ou billing real. A configuração do servidor de homologação permanece dependente do acesso à conta Render que contém o serviço existente.

## Questões em aberto

Nenhuma decisão de produto: o endereço e o comportamento de fallback já existem no projeto. O acesso à conta Render é uma dependência operacional separada.
