/**
 * Persistência em arquivo para o estado do programa quando NÃO há MongoDB.
 *
 * Sem isto, `MONGODB_URI` ausente significa estado só em memória: qualquer
 * restart do backend (deploy, sleep do plano free, crash) apagava semana atual,
 * streak, notas de quiz, nivelamento e data de início — ou seja, o programa de
 * 26 semanas recomeçava do zero sem avisar o aluno.
 *
 * Não substitui o MongoDB (em hosts com disco efêmero o arquivo some junto com
 * a instância), mas cobre o caso comum: reinício do processo no mesmo host.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const DATA_DIR = process.env.PROGRAM_STATE_DIR || path.resolve(__dirname, '../data');
const STATE_FILE = path.join(DATA_DIR, 'program_state.json');

let enabled = true;

/** Desliga a persistência em arquivo (ex.: quando o Mongo assume). */
export function setFileStoreEnabled(flag) {
  enabled = !!flag;
}

export function isFileStoreEnabled() {
  return enabled;
}

export function stateFilePath() {
  return STATE_FILE;
}

/** @returns {{state:object|null, sessions:object[]}|null} */
export function readSnapshot() {
  if (!enabled) return null;
  try {
    if (!fs.existsSync(STATE_FILE)) return null;
    const raw = fs.readFileSync(STATE_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    return {
      state: parsed?.state || null,
      sessions: Array.isArray(parsed?.sessions) ? parsed.sessions : [],
    };
  } catch (e) {
    console.warn('[stateFile] leitura falhou:', e.message);
    return null;
  }
}

/**
 * Grava o snapshot. Escrita atômica (tmp + rename) para não corromper o arquivo
 * se o processo morrer no meio.
 */
export function writeSnapshot({ state, sessions }) {
  if (!enabled) return false;
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const payload = JSON.stringify(
      { version: 1, saved_at: new Date().toISOString(), state, sessions },
      null,
      2
    );
    const tmp = `${STATE_FILE}.tmp`;
    fs.writeFileSync(tmp, payload, 'utf8');
    fs.renameSync(tmp, STATE_FILE);
    return true;
  } catch (e) {
    console.warn('[stateFile] gravação falhou:', e.message);
    return false;
  }
}
