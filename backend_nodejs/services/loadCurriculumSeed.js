/**
 * Load and normalize elias_curriculum_seed.json.
 * Supports:
 *  - official format: { version, phases, weeks: [...] }
 *  - legacy format: [ week, ... ]
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export const DEFAULT_SEED_PATH = path.join(__dirname, '..', 'seeds', 'elias_curriculum_seed.json');

/**
 * @param {string} [seedPath]
 * @returns {{ version: number|null, phases: object[], weeks: object[] }}
 */
export function loadCurriculumSeedFile(seedPath = DEFAULT_SEED_PATH) {
  if (!fs.existsSync(seedPath)) {
    throw new Error(`Curriculum seed not found: ${seedPath}`);
  }
  const raw = JSON.parse(fs.readFileSync(seedPath, 'utf-8'));
  return normalizeCurriculumSeed(raw);
}

/**
 * @param {unknown} raw
 */
export function normalizeCurriculumSeed(raw) {
  if (Array.isArray(raw)) {
    return { version: null, phases: [], weeks: raw };
  }
  if (raw && typeof raw === 'object' && Array.isArray(raw.weeks)) {
    return {
      version: raw.version ?? 1,
      phases: Array.isArray(raw.phases) ? raw.phases : [],
      weeks: raw.weeks,
    };
  }
  throw new Error('Invalid curriculum seed: expected array or { weeks: [...] }');
}
