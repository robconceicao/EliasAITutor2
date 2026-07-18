# Deploy no Render — Elias AI Tutor Backend

URL de produção atual: **https://eliasaitutor2.onrender.com**

## Por que o TTS estava mudo

```bash
curl -s https://eliasaitutor2.onrender.com/health
# {"ok":true,"elevenLabsKey":false,...}
```

Sem `ELEVENLABS_API_KEY` (ou alias) no Environment do Render, o backend emite `voice_open_failed` / `elevenlabs_api_key_missing`.

---

## A) Serviço já existente (recomendado — 5 minutos)

### 1. Código no GitHub

1. Merge (ou push) do branch com o fix TTS em **`main`**  
   (Render costuma auto-deploy a partir de `main`).
2. Confirme no GitHub: repo `robconceicao/EliasAITutor2`.

### 2. Settings do serviço

Dashboard: [https://dashboard.render.com](https://dashboard.render.com) → **eliasaitutor2**

| Campo | Valor |
|--------|--------|
| **Root Directory** | `backend_nodejs` |
| **Build Command** | `npm install` |
| **Start Command** | `npm start` |
| **Health Check Path** | `/health` |
| **Branch** | `main` |
| **Auto-Deploy** | Yes |

### 3. Environment variables (crítico)

**Environment** → **Add Environment Variable**:

| Key | Value | Obrigatório |
|-----|--------|-------------|
| `ELEVENLABS_API_KEY` | mesmo valor de `My-English-Coach-Key` no `local.properties` | **SIM (TTS)** |
| `ANTHROPIC_API_KEY` | … | recomendado |
| `GEMINI_API_KEY` | … | recomendado |
| `DEEPSEEK_API_KEY` | … | recomendado |
| `GROQ_API_KEY` | … | recomendado (LLM rápido + Whisper Echo) |
| `NODE_VERSION` | `20.18.0` | recomendado |
| `MAIN_CHAT_VOICE_ID` | `TX3LPaxmHKxFdv7VOQHJ` | opcional (default Liam) |
| `FALLBACK_VOICE_ID` | `iP95p4xoKVk53GoZ742B` | opcional (Chris) |
| `ELEVENLABS_STREAM_MODEL` | `eleven_flash_v2_5` | opcional |
| `ELEVENLABS_OUTPUT_FORMAT` | `pcm_24000` | opcional |
| `MONGODB_URI` | … | opcional |

Aliases de chave ElevenLabs aceitos pelo código:  
`ELEVENLABS_API_KEY`, `My-English-Coach-Key`, `MY_ENGLISH_COACH_KEY`.

### 4. Manual Deploy

**Manual Deploy** → **Deploy latest commit**

### 5. Verificar

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

Free tier “dorme” após inatividade — a 1ª request pode demorar ~30–60s (cold start).

---

## B) Blueprint novo (`render.yaml`)

Na raiz do repo existe `render.yaml`.

1. Dashboard → **New** → **Blueprint**
2. Conecte o repo `EliasAITutor2`
3. Preencha as variáveis com `sync: false` (secrets)
4. Apply

Se o serviço `eliasaitutor2` já existir, prefira **A)** e só alinha settings + env.

---

## C) Checklist pós-deploy

| # | Teste | OK? |
|---|--------|-----|
| 1 | `/health` → `elevenLabsKey: true` | |
| 2 | App Android conecta (banner Online) | |
| 3 | Chat: Elias responde com **voz** | |
| 4 | Echo Mode: “Ouça Elias” toca áudio | |
| 5 | Programa → sessão sem chips de nível | |

---

## Logs úteis no Render

**Logs** do serviço deve mostrar no boot:

```
[boot] TTS main=TX3LPaxmHKxFdv7VOQHJ ... elevenLabsKey=true source=ELEVENLABS_API_KEY
```

Se aparecer:

```
[boot] ⚠️ ELEVENLABS_API_KEY missing
```

a variável não foi salva ou o redeploy não rodou.

---

## Segurança

- Nunca commitar `local.properties` / `.env`
- Nunca colar a chave no `render.yaml` como `value:`
- Rotacione a chave se vazou em log/chat

---

## App Android

`BACKEND_URL` em `local.properties` já aponta para:

```properties
BACKEND_URL=https://eliasaitutor2.onrender.com
```

Rebuild do app só é necessário se mudou `My-English-Coach-Key` no client (fallback local). O TTS principal usa o backend.
