/**
 * Verifica a conexão com o MongoDB ANTES de subir o backend.
 *
 * Rodar:
 *   cd backend_nodejs
 *   node test_mongo_connection.js
 *
 * Lê MONGODB_URI de .env / local.properties (mesma ordem do server.js) ou do
 * ambiente. Faz um round-trip real de escrita e leitura numa coleção temporária.
 */
import mongoose from 'mongoose';
import dotenv from 'dotenv';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

dotenv.config({ path: path.join(__dirname, '.env') });

const localProps = path.resolve(__dirname, '../local.properties');
if (fs.existsSync(localProps)) {
  for (const line of fs.readFileSync(localProps, 'utf8').split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#') || !t.includes('=')) continue;
    const [k, ...rest] = t.split('=');
    if (!process.env[k]) process.env[k] = rest.join('=').trim();
  }
}

const uri = process.env.MONGODB_URI;

/** Nunca imprimir a senha no terminal. */
function maskUri(u) {
  return String(u).replace(/\/\/([^:]+):([^@]+)@/, (_m, user) => `//${user}:****@`);
}

function fail(msg, hints = []) {
  console.error(`\n❌ ${msg}`);
  for (const h of hints) console.error(`   • ${h}`);
  process.exit(1);
}

if (!uri) {
  fail('MONGODB_URI não encontrada.', [
    'Adicione a linha MONGODB_URI=... em local.properties (raiz do projeto) ou em backend_nodejs/.env',
    'Sem ela o backend roda em modo arquivo (data/program_state.json).',
  ]);
}

console.log('🔗 URI:', maskUri(uri));

// Erros de formato que dão dor de cabeça e não aparecem como "senha errada"
if (!/^mongodb(\+srv)?:\/\//.test(uri)) {
  fail('A URI precisa começar com mongodb:// ou mongodb+srv://');
}
const afterHost = uri.split('@')[1] || '';
const dbPart = afterHost.split('/')[1];
if (!dbPart || dbPart.startsWith('?')) {
  console.warn(
    '⚠️  A URI não especifica um nome de banco antes do "?".\n' +
      '   Recomendado: ...mongodb.net/elias?retryWrites=true&w=majority'
  );
}
const pwd = (uri.match(/\/\/[^:]+:([^@]+)@/) || [])[1];
if (pwd && /[@:/?#[\]]/.test(decodeURIComponent(pwd)) && pwd === decodeURIComponent(pwd)) {
  console.warn(
    '⚠️  A senha parece conter caractere especial sem URL-encode (@ : / ? # [ ]).\n' +
      '   Isso quebra a conexão. Troque a senha por uma alfanumérica ou faça o encode.'
  );
}

try {
  console.log('⏳ Conectando (timeout 15s)...');
  await mongoose.connect(uri, { serverSelectionTimeoutMS: 15000 });
  console.log('✅ Conectado.');

  const db = mongoose.connection.db;
  console.log('   Banco:', mongoose.connection.name);

  // Round-trip real: escrever, ler e apagar
  const probe = db.collection('_elias_probe');
  const doc = { at: new Date(), from: 'test_mongo_connection' };
  const ins = await probe.insertOne(doc);
  const read = await probe.findOne({ _id: ins.insertedId });
  await probe.deleteOne({ _id: ins.insertedId });
  if (!read) fail('Escrita funcionou mas a leitura voltou vazia.');
  console.log('✅ Escrita e leitura OK (permissão readWrite confirmada).');

  const names = (await db.listCollections().toArray()).map((c) => c.name);
  const program = names.filter((n) => n.startsWith('program_') || n.startsWith('practice_') || n.startsWith('user_program'));
  console.log(
    '   Coleções do programa já existentes:',
    program.length ? program.join(', ') : '(nenhuma ainda — serão criadas no primeiro boot)'
  );

  await mongoose.disconnect();
  console.log('\n🎉 Tudo certo. Suba o backend com: node server.js');
  console.log('   Depois confirme em /health que "mongo": true');
  process.exit(0);
} catch (e) {
  const msg = e.message || String(e);
  const hints = [];
  if (/authentication failed|bad auth/i.test(msg)) {
    hints.push('Usuário ou senha incorretos (Atlas → Database Access).');
    hints.push('Se a senha tem @ : / ? # ela precisa de URL-encode — ou gere uma só com letras e números.');
  } else if (/ETIMEDOUT|ServerSelectionError|timed out|ENOTFOUND|EAI_AGAIN|querySrv/i.test(msg)) {
    hints.push('Atlas → Network Access → Add IP Address → Allow access from anywhere (0.0.0.0/0).');
    hints.push('Confira se o hostname do cluster na URI está correto.');
  } else if (/not authorized/i.test(msg)) {
    hints.push('O usuário existe mas não tem permissão. Dê "Read and write to any database".');
  }
  hints.push('A URI deve ser a do driver Node.js (Atlas → Connect → Drivers).');
  fail(`Falha na conexão: ${msg}`, hints);
}
