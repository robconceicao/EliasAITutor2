/**
 * Mongoose schemas for Modo Programa (F1–F8).
 * New files only — does not alter existing Conversa schema.
 */
import mongoose from 'mongoose';

const ChunkSchema = new mongoose.Schema(
  {
    en: String,
    ipa: String,
    pt: String,
    use: String,
    audioPath: String, // relative path under cache/chunks when pre-generated
  },
  { _id: false }
);

export const ProgramWeekSchema = new mongoose.Schema(
  {
    week: { type: Number, required: true, unique: true, min: 1, max: 26 },
    phase: { type: Number, required: true, min: 1, max: 4 },
    level: { type: String, required: true },
    title: { type: String, required: true },
    grammar: { type: String, default: '' },
    lexis: { type: String, default: '' },
    persona_city: { type: String, default: 'New York' },
    conversation_prompt: { type: String, default: '' },
    objectives: { type: [String], default: [] },
    chunks: { type: [ChunkSchema], default: [] },
    anki_sentences: { type: [String], default: [] },
  },
  { collection: 'program_weeks' }
);

export const UserProgramStateSchema = new mongoose.Schema(
  {
    // Singleton key for single-user app (V2)
    key: { type: String, default: 'default', unique: true },
    start_date: { type: String, required: true }, // YYYY-MM-DD
    current_week: { type: Number, default: 1, min: 1, max: 26 },
    week_mode: { type: String, enum: ['auto', 'manual'], default: 'auto' },
    reminder_time: { type: String, default: null }, // HH:mm or null
    daily_goal_minutes: { type: Number, default: 30 },
  },
  { collection: 'user_program_state' }
);

export const PracticeSessionSchema = new mongoose.Schema(
  {
    id: { type: String, required: true, unique: true },
    week: { type: Number, required: true },
    type: { type: String, enum: ['themed', 'quick', 'chunks'], required: true },
    started_at: { type: Date, required: true },
    ended_at: { type: Date, default: null },
    duration_seconds: { type: Number, default: 0 },
    feedback_json: { type: mongoose.Schema.Types.Mixed, default: null },
    feedback_status: {
      type: String,
      enum: ['none', 'pending', 'ready', 'failed'],
      default: 'none',
    },
  },
  { collection: 'practice_sessions' }
);

export const ProgramWeek =
  mongoose.models.ProgramWeek || mongoose.model('ProgramWeek', ProgramWeekSchema);
export const UserProgramState =
  mongoose.models.UserProgramState ||
  mongoose.model('UserProgramState', UserProgramStateSchema);
export const PracticeSession =
  mongoose.models.PracticeSession ||
  mongoose.model('PracticeSession', PracticeSessionSchema);
