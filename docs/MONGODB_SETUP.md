# Configurar MongoDB — Elias AI Tutor

> **Raiz do projeto:** `C:\Users\robtc\AndroidStudioProjects\EliasAITutor2`
> Todos os comandos deste guia rodam a partir de `<raiz>\backend_nodejs`.

Por que isto importa: sem MongoDB, o estado do programa (semana atual, nivelamento, streak, notas de quiz, histórico de sessões) vive em `backend_nodejs/data/program_state.json`. Isso sobrevive a um restart do processo, **mas não à troca de instância** — que é exatamente o que o plano free do Render faz quando o serviço dorme. Com MongoDB, seu progresso das 26 semanas fica seguro.

O plano **M0 do Atlas é gratuito e permanente** (512 MB). Sobra muito: o programa inteiro usa poucos MB.

---

## 1. Criar o cluster (≈10 min)

1. Acesse <https://www.mongodb.com/cloud/atlas/register> e crie a conta.
2. **Deploy your cluster** → escolha **M0 / Free**.
3. Provider e região: escolha a mais próxima do seu backend no Render. Se o serviço está em Oregon, use **AWS / us-west-2**; se está em Frankfurt, **AWS / eu-central-1**. Latência menor = resposta mais rápida no app.
4. Nome do cluster: `elias` (ou o que preferir).
5. **Create Deployment**.

## 2. Criar o usuário do banco

Na tela que aparece logo após criar (ou em **Database Access → Add New Database User**):

- Username: `elias_app`
- Password: use **Autogenerate Secure Password** e copie.
  > ⚠️ Se você digitar a senha manualmente, **evite** os caracteres `@ : / ? # [ ]`. Eles quebram a connection string a menos que sejam URL-encoded. Senha só com letras e números evita o problema inteiro.
- Database User Privileges: **Read and write to any database**.
- **Add User**.

## 3. Liberar o acesso de rede

**Network Access → Add IP Address → Allow Access from Anywhere** (`0.0.0.0/0`) → **Confirm**.

> O Render não oferece IP fixo no plano free, então restringir por IP não é viável. A proteção real aqui é a senha do usuário — por isso ela precisa ser forte e nunca ser commitada.

## 4. Pegar a connection string

**Database → Connect → Drivers → Node.js**. Você recebe algo assim:

```
mongodb+srv://elias_app:<db_password>@elias.ab1cd.mongodb.net/?retryWrites=true&w=majority
```

Faça **duas** edições:

1. Troque `<db_password>` pela senha real.
2. Insira o nome do banco (`elias`) **antes** do `?`:

```
mongodb+srv://elias_app:SENHA_REAL@elias.ab1cd.mongodb.net/elias?retryWrites=true&w=majority
```

Sem o nome do banco, o Mongoose usa `test` — funciona, mas seus dados vão parar num lugar inesperado.

## 5. Configurar localmente

Adicione a linha em `local.properties` (na raiz do projeto, **não** em `backend_nodejs/`):

```properties
MONGODB_URI=mongodb+srv://elias_app:SENHA_REAL@elias.ab1cd.mongodb.net/elias?retryWrites=true&w=majority
```

`local.properties` já está no `.gitignore` — a senha não vai para o repositório.

Teste **antes** de subir o backend. No PowerShell, a partir de qualquer lugar:

```powershell
cd C:\Users\robtc\AndroidStudioProjects\EliasAITutor2\backend_nodejs
npm run test:mongo
```

Saída esperada:

```
✅ Conectado.
✅ Escrita e leitura OK (permissão readWrite confirmada).
🎉 Tudo certo.
```

Se falhar, o script diz exatamente o que checar (senha, Network Access ou permissão).

## 6. Configurar no Render

**Dashboard → serviço `eliasaitutor2` → Environment → Add Environment Variable**:

| Key | Value |
|---|---|
| `MONGODB_URI` | a mesma string do passo 4 |

Salve. O Render redeploya sozinho. A variável já está declarada em `render.yaml` com `sync: false`, então o valor fica só no dashboard — nunca no Git.

## 7. Confirmar que funcionou

No PowerShell (`curl` do Windows é um alias diferente — use `curl.exe` ou o comando abaixo):

```powershell
Invoke-RestMethod https://eliasaitutor2.onrender.com/health | ConvertTo-Json
```

Procure por:

```json
{ "mongo": true, "mongoStatus": "connected", "programWeeksLoaded": 26 }
```

Nos logs do Render você deve ver:

```
✅ Conectado ao MongoDB
📚 Curriculum seed v1 loaded: 26 weeks
```

---

## Se algo der errado

O backend **não quebra** quando o Mongo falha: ele loga o erro, cai para persistência em arquivo e continua servindo as 26 semanas normalmente. Você percebe pelo `/health`.

| `mongoStatus` | O que significa | O que fazer |
|---|---|---|
| `disabled` | `MONGODB_URI` não configurada | Passo 5 ou 6 |
| `error: bad auth...` | Usuário ou senha errados | Recrie o usuário no Database Access; use senha alfanumérica |
| `error: querySrv ENOTFOUND` | Hostname do cluster errado | Copie a URI de novo em Connect → Drivers |
| `error: ...timed out` | IP bloqueado | Network Access → `0.0.0.0/0` |
| `disconnected` | Caiu depois de conectar | O Mongoose reconecta sozinho; enquanto isso o estado vai para arquivo |

## Migrando o progresso que já existe

Se você já usou o app antes de configurar o Mongo, o progresso está em `backend_nodejs/data/program_state.json`. Na primeira leitura após o Mongo conectar, o backend encontra a coleção vazia, usa o estado que está em memória (carregado desse arquivo) e o grava no Mongo. Ou seja: **suba o backend uma vez com o arquivo presente** e a migração acontece sozinha. Confirme com:

```powershell
Invoke-RestMethod https://eliasaitutor2.onrender.com/program/state | ConvertTo-Json
```

O app Android tem uma trava adicional: se o backend responder um estado "virgem" (Semana 1, sem nivelamento) enquanto o cache local tem progresso real, o app **restaura o estado no servidor** em vez de apagar suas semanas.

## Custo

M0 é gratuito para sempre, sem cartão. Limites: 512 MB e conexões compartilhadas — muito acima do que um app single-user consome. O Atlas pausa clusters M0 após 60 dias sem nenhuma conexão; como o app conecta a cada sessão, isso não deve acontecer.
