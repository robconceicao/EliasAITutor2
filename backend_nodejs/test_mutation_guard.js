/**
 * Guarda de mutação — os testes deste projeto realmente testam?
 *
 * Um teste que passa não prova nada: prova que passou. O que prova é ele **falhar**
 * quando a implementação quebra. Este script quebra o código de propósito, uma
 * alteração por vez, e exige que a suíte reprove cada uma.
 *
 * Existe porque o verificador do ciclo 3 recusou, com razão, a afirmação "cinco
 * mutações, cinco mortas" numa mensagem de commit: mutações que rodam em pasta
 * temporária e somem não são fato do repositório, são alegação do autor. Agora
 * qualquer um roda `npm run test:mutation` e confere.
 *
 * Cada entrada abaixo é um bug que já existiu de verdade ou que a spec proíbe.
 * Quando uma mutação SOBREVIVE, o achado não é sobre o código — é sobre o teste.
 *
 * Segurança: recusa rodar com os arquivos-alvo modificados no git, restaura tudo no
 * finally e confere a restauração byte a byte antes de sair. Se algo der errado no
 * meio, `git checkout -- backend_nodejs/` devolve a árvore.
 */
import assert from 'assert';
import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SUITE = path.join(__dirname, 'test_tts_failover.js');

/**
 * @typedef {{id:string, porque:string, arquivo:string, de:string, para:string}} Mutacao
 * `de` precisa aparecer exatamente uma vez no arquivo — se não aparecer, o script
 * falha em vez de reportar um falso "morta", que é o pior resultado possível aqui.
 */
/** @type {Mutacao[]} */
const MUTACOES = [
  {
    id: 'M1',
    porque: 'F1 do ciclo 3: goTextOnly achatava first_audio_byte_timeout em tts_failed',
    arquivo: 'server.js',
    de: 'isTaxonomyLabel(cause) ? cause : ttsFailureReason(err)',
    para: 'ttsFailureReason(err)',
  },
  {
    id: 'M2',
    porque: 'F2 do ciclo 3: 400 com authentication_error virava http_400 — o status do incidente original',
    arquivo: 'services/elevenLabsClient.js',
    de: "if (tipo.includes('authentication') || tipo.includes('invalid_api_key')) return 'key_rejected';",
    para: '',
  },
  {
    id: 'M3',
    porque: 'D6/A8: deep precisa provar síntese mesmo com a conta OK, senão cota some',
    arquivo: 'services/elevenLabsClient.js',
    de: "if (account.ok && depth !== 'deep') {",
    para: 'if (account.ok) {',
  },
  {
    id: 'M4',
    porque: 'D6: cache por profundidade — um shallow inconclusivo não pode servir a um deep',
    arquivo: 'services/elevenLabsClient.js',
    de: 'const fingerprint = `${depth}:',
    para: 'const fingerprint = `${',
  },
  {
    id: 'M5',
    porque: 'Cota estourada não pode ser confundida com chave recusada: o conserto é outro',
    arquivo: 'services/elevenLabsClient.js',
    de: "if (status === 429) return 'quota_exceeded';",
    para: '',
  },
  {
    id: 'M6',
    porque: 'D5/F3: sonda de conta apagava falha registrada — state ready ao lado de lastFailure de cota',
    arquivo: 'services/ttsProvider.js',
    de: "return observed.lastFailure ? observed.state : 'ready';",
    para: "return 'ready';",
  },
  {
    id: 'M7',
    porque: 'D5: sem prova e sem histórico o estado é "não verificado", nunca "pronto"',
    arquivo: 'services/ttsProvider.js',
    de: "return observed.lastFailure ? observed.state : 'unverified';",
    para: "return 'ready';",
  },
  {
    id: 'M8',
    porque: 'Sem chave, a razão é a ausência — não adianta culpar a API',
    arquivo: 'services/ttsProvider.js',
    de: "if (!hasTtsKey()) return 'no_key_configured';",
    para: '',
  },
  {
    id: 'M9',
    porque: 'providerKeyEnvNames devolve cópia: o chamador não pode mutar o registro',
    arquivo: 'services/ttsProvider.js',
    de: 'return [...KEY_ENV_NAMES];',
    para: 'return KEY_ENV_NAMES;',
  },
  {
    id: 'M10',
    porque: 'O erro real do device (400 com authentication_error) precisa contar como falha de conta',
    arquivo: 'services/ttsProvider.js',
    de: "msg.includes('authentication_error') ||",
    para: '',
  },
];

// ─── guarda de segurança ────────────────────────────────────
const alvos = [...new Set(MUTACOES.map((m) => m.arquivo))];
const relativos = alvos.map((a) => `backend_nodejs/${a}`);

try {
  execFileSync('git', ['diff', '--quiet', '--', ...relativos], {
    cwd: path.join(__dirname, '..'),
    stdio: 'pipe',
  });
} catch {
  console.error(
    '❌ Há alterações não commitadas nos arquivos-alvo:\n   ' +
      relativos.join('\n   ') +
      '\n   Commite ou guarde antes — este script reescreve esses arquivos.'
  );
  process.exit(1);
}

const originais = new Map(
  alvos.map((a) => [a, fs.readFileSync(path.join(__dirname, a), 'utf8')])
);

function rodarSuite() {
  try {
    execFileSync('node', [SUITE], { stdio: 'pipe' });
    return true; // passou
  } catch {
    return false; // falhou
  }
}

// ─── execução ───────────────────────────────────────────────
const sobreviventes = [];
let baseOk = true;

try {
  // Sanidade: sem mutação, a suíte tem que passar. Se não passar, nada abaixo vale.
  if (!rodarSuite()) {
    baseOk = false;
    console.error('❌ A suíte já falha sem nenhuma mutação. Conserte isso primeiro.');
  } else {
    for (const m of MUTACOES) {
      const caminho = path.join(__dirname, m.arquivo);
      const original = originais.get(m.arquivo);
      const ocorrencias = original.split(m.de).length - 1;
      assert.strictEqual(
        ocorrencias,
        1,
        `${m.id}: o trecho a mutar aparece ${ocorrencias}x em ${m.arquivo} — precisa ser exatamente 1. ` +
          'Um padrão que não casa produziria um falso "morta", que é pior que não testar.'
      );

      fs.writeFileSync(caminho, original.replace(m.de, m.para));
      const passouMutado = rodarSuite();
      fs.writeFileSync(caminho, original);

      if (passouMutado) sobreviventes.push(m);
      console.log(
        `  ${passouMutado ? '⚠️  SOBREVIVEU' : '✅ morta     '}  ${m.id} · ${m.porque}`
      );
    }
  }
} finally {
  // Restaura sempre, e confere que restaurou.
  for (const [rel, conteudo] of originais) {
    const caminho = path.join(__dirname, rel);
    if (fs.readFileSync(caminho, 'utf8') !== conteudo) fs.writeFileSync(caminho, conteudo);
    assert.strictEqual(
      fs.readFileSync(caminho, 'utf8'),
      conteudo,
      `FALHA AO RESTAURAR ${rel} — rode: git checkout -- backend_nodejs/`
    );
  }
}

if (!baseOk) process.exit(1);

if (sobreviventes.length > 0) {
  console.error(
    `\n❌ ${sobreviventes.length} de ${MUTACOES.length} mutações sobreviveram. ` +
      'A suíte passa com o código quebrado nesses pontos — o problema é o teste, não a mutação:\n' +
      sobreviventes.map((m) => `   ${m.id} · ${m.porque}`).join('\n')
  );
  process.exit(1);
}

console.log(`\n✅ ${MUTACOES.length} mutações, ${MUTACOES.length} mortas — a suíte tem dentes`);
