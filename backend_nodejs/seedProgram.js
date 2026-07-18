/**
 * Idempotent seed for program_weeks (F1).
 * Usage: node seedProgram.js [--tts]
 *   --tts  also pre-generate chunk audio via ElevenLabs (F7)
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import mongoose from 'mongoose';
import dotenv from 'dotenv';
import {
  upsertWeeks,
  upsertQuizzes,
  setMongoEnabled,
  getWeekCount,
  getAllWeeks,
} from './services/programStore.js';
import { pregenerateWeekChunks } from './services/elevenLabsClient.js';
import { loadCurriculumSeedFile, DEFAULT_SEED_PATH } from './services/loadCurriculumSeed.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

dotenv.config();

// Load local.properties like server.js
const localPropsPath = path.resolve(__dirname, '../local.properties');
if (fs.existsSync(localPropsPath)) {
  const lines = fs.readFileSync(localPropsPath, 'utf-8').split('\n');
  lines.forEach((line) => {
    const trimmed = line.trim();
    if (trimmed && !trimmed.startsWith('#') && trimmed.includes('=')) {
      const [key, ...rest] = trimmed.split('=');
      const value = rest.join('=').trim();
      if (!process.env[key]) process.env[key] = value;
    }
  });
}

const seedPath = DEFAULT_SEED_PATH;
const withTts = process.argv.includes('--tts');

async function main() {
  if (!fs.existsSync(seedPath)) {
    console.error('❌ Seed file not found:', seedPath);
    process.exit(1);
  }

  const { version, phases, weeks } = loadCurriculumSeedFile(seedPath);
  console.log(`📖 Seed v${version ?? '?'} · ${phases.length} phases · ${weeks.length} weeks`);
  if (!Array.isArray(weeks) || weeks.length !== 26) {
    console.error('❌ Seed must contain exactly 26 weeks, got', weeks?.length);
    process.exit(1);
  }

  if (process.env.MONGODB_URI) {
    try {
      await mongoose.connect(process.env.MONGODB_URI);
      setMongoEnabled(true);
      console.log('✅ MongoDB connected for seed');
    } catch (e) {
      console.warn('⚠️ MongoDB failed, seeding memory only:', e.message);
      setMongoEnabled(false);
    }
  } else {
    console.log('⚠️ MONGODB_URI not set — seeding in-memory store only');
  }

  let payload = weeks;
  if (withTts) {
    console.log('🎙️ Pre-generating chunk audio (ElevenLabs)...');
    payload = [];
    for (const w of weeks) {
      const updated = await pregenerateWeekChunks(w);
      payload.push(updated);
      console.log(`  week ${w.week}: ${updated.chunks.filter((c) => c.audioPath).length}/10 audio`);
    }
  }

  const count = await upsertWeeks(payload);
  console.log(`✅ Upserted ${count} weeks (idempotent by week number)`);

  // Second pass to verify idempotency
  await upsertWeeks(payload);
  const all = await getAllWeeks();
  console.log(`✅ Re-run complete — still ${all.length} weeks (expected 26)`);
  if (all.length !== 26) {
    process.exitCode = 1;
  }

  // B.5 quiz seed (separate collection, join by week)
  const quizPath = path.join(__dirname, 'seeds', 'elias_quiz_seed.json');
  if (fs.existsSync(quizPath)) {
    const quizSeed = JSON.parse(fs.readFileSync(quizPath, 'utf8'));
    const qn = await upsertQuizzes(quizSeed);
    console.log(
      `✅ Upserted ${qn} quizzes (pass ${quizSeed.passing_score_percent}%)`
    );
    if (qn !== 26) process.exitCode = 1;
  } else {
    console.warn('⚠️ Quiz seed missing — skip:', quizPath);
  }

  if (mongoose.connection.readyState === 1) {
    await mongoose.disconnect();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
