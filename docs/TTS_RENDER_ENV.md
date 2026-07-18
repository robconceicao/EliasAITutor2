# TTS — Configuração da chave ElevenLabs (Render)

## Causa raiz do mute + `voice_open_failed` (jul/2026)

O app aponta para `BACKEND_URL=https://eliasaitutor2.onrender.com`.

Health do deploy mostrou:

```json
{ "ok": true, "elevenLabsKey": false, ... }
```

Sem chave no host, o backend emite `tts_unavailable` com `voice_open_failed` / `elevenlabs_api_key_missing` e o Elias fica mudo (texto ainda chega).

A chave local em `local.properties` (`My-English-Coach-Key`) **não** é enviada ao Render automaticamente.

## Correção obrigatória no Render

1. Abra o serviço **eliasaitutor2** no [Render Dashboard](https://dashboard.render.com).
2. **Environment** → adicione:

| Key | Value |
|-----|--------|
| `ELEVENLABS_API_KEY` | *(mesmo valor de `My-English-Coach-Key` do local.properties)* |

Aliases também aceitos pelo backend (v2.1+):

- `My-English-Coach-Key`
- `MY_ENGLISH_COACH_KEY`
- `ELEVEN_LABS_API_KEY`
- `ELEVENLABS_KEY`

3. (Opcional) voz masculina americana:

| Key | Default |
|-----|---------|
| `MAIN_CHAT_VOICE_ID` | Liam `TX3LPaxmHKxFdv7VOQHJ` |
| `FALLBACK_VOICE_ID` | Chris `iP95p4xoKVk53GoZ742B` |
| `ELEVENLABS_STREAM_MODEL` | `eleven_flash_v2_5` |
| `ELEVENLABS_OUTPUT_FORMAT` | `pcm_24000` |

4. **Save** → aguarde redeploy / restart.
5. Confirme:

```bash
curl -s https://eliasaitutor2.onrender.com/health
```

Esperado:

```json
{
  "ok": true,
  "elevenLabsKey": true,
  "elevenLabsKeySource": "ELEVENLABS_API_KEY",
  "mainChatVoiceId": "TX3LPaxmHKxFdv7VOQHJ",
  "streamModel": "eleven_flash_v2_5"
}
```

## Fallbacks no app (v2.1)

1. **Backend stream-input** (preferido) → Opus frames.
2. **Backend REST complete** se o WebSocket falhar mas a chave existir.
3. **Cliente Android REST** (chave via `My-English-Coach-Key` no `local.properties` → BuildConfig) se o servidor continuar sem chave.

## Deploy do código

O branch com o fix é `feat/task-final-tts-echo-complete`. Após merge em `main` (ou reapontar o Render para este branch), redeploy.

**Nunca** commitar `local.properties` nem `.env` com secrets.
