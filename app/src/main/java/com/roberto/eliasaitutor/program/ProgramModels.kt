package com.roberto.eliasaitutor.program

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProgramChunk(
    val en: String = "",
    val ipa: String = "",
    val pt: String = "",
    val use: String = "",
    val audioPath: String? = null,
)

@Serializable
data class ProgramWeek(
    val week: Int = 0,
    val phase: Int = 1,
    val level: String = "",
    val title: String = "",
    val grammar: String = "",
    val lexis: String = "",
    @SerializedName("persona_city")
    @SerialName("persona_city")
    val personaCity: String = "",
    @SerializedName("conversation_prompt")
    @SerialName("conversation_prompt")
    val conversationPrompt: String = "",
    val objectives: List<String> = emptyList(),
    val chunks: List<ProgramChunk> = emptyList(),
    @SerializedName("anki_sentences")
    @SerialName("anki_sentences")
    val ankiSentences: List<String> = emptyList(),
)

@Serializable
data class UserProgramState(
    @SerializedName("start_date")
    @SerialName("start_date")
    val startDate: String = "",
    @SerializedName("current_week")
    @SerialName("current_week")
    val currentWeek: Int = 1,
    @SerializedName("week_mode")
    @SerialName("week_mode")
    val weekMode: String = "auto", // auto | manual
    @SerializedName("reminder_time")
    @SerialName("reminder_time")
    val reminderTime: String? = null,
    @SerializedName("daily_goal_minutes")
    @SerialName("daily_goal_minutes")
    val dailyGoalMinutes: Int = 30,
    // B.3 adaptive tutor
    @SerializedName("held_back")
    @SerialName("held_back")
    val heldBack: Boolean = false,
    @SerializedName("review_since")
    @SerialName("review_since")
    val reviewSince: String? = null,
    @SerializedName("total_paused_days")
    @SerialName("total_paused_days")
    val totalPausedDays: Int = 0,
    @SerializedName("deficient_topics")
    @SerialName("deficient_topics")
    val deficientTopics: List<String>? = null,
    /** Highest week cleared by quiz/checkpoint (mastery hard-gate). */
    @SerializedName("mastery_cleared_week")
    @SerialName("mastery_cleared_week")
    val masteryClearedWeek: Int = 0,
    /** Day 1 = start_date (inclusive). */
    @SerializedName("program_day")
    @SerialName("program_day")
    val programDay: Int = 1,
    /** Highest week the student may open (quiz-gated). */
    @SerializedName("unlocked_week")
    @SerialName("unlocked_week")
    val unlockedWeek: Int = 1,
    @SerializedName("current_week_quiz_passed")
    @SerialName("current_week_quiz_passed")
    val currentWeekQuizPassed: Boolean = false,
    @SerializedName("next_week_locked")
    @SerialName("next_week_locked")
    val nextWeekLocked: Boolean = true,
    @SerializedName("progress_hint")
    @SerialName("progress_hint")
    val progressHint: String = "",
    /** Semana inicial definida pelo nivelamento (1 = do zero). */
    @SerializedName("start_week")
    @SerialName("start_week")
    val startWeek: Int = 1,
    /** Semana que o calendário sozinho indicaria (sem o gate de quiz). */
    @SerializedName("calendar_week")
    @SerialName("calendar_week")
    val calendarWeek: Int = 1,
    /** true quando o calendário passou da semana liberada pelo quiz. */
    @SerializedName("gate_blocking_calendar")
    @SerialName("gate_blocking_calendar")
    val gateBlockingCalendar: Boolean = false,
    @SerializedName("placement_done")
    @SerialName("placement_done")
    val placementDone: Boolean = false,
    @SerializedName("placement_level")
    @SerialName("placement_level")
    val placementLevel: String? = null,
    @SerializedName("placement_score")
    @SerialName("placement_score")
    val placementScore: Int? = null,
)

// ─── Nivelamento (placement) ──────────────────────────────────

@Serializable
data class PlacementQuestion(
    val tier: Int = 1,
    val level: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
)

@Serializable
data class PlacementTierInfo(
    val tier: Int = 1,
    val level: String = "",
    @SerializedName("start_week")
    @SerialName("start_week")
    val startWeek: Int = 1,
)

@Serializable
data class PlacementPayload(
    val total: Int = 0,
    @SerializedName("tier_pass_ratio")
    @SerialName("tier_pass_ratio")
    val tierPassRatio: Double = 0.75,
    val tiers: List<PlacementTierInfo> = emptyList(),
    val questions: List<PlacementQuestion> = emptyList(),
)

@Serializable
data class PlacementTierResult(
    val tier: Int = 1,
    val level: String = "",
    @SerializedName("start_week")
    @SerialName("start_week")
    val startWeek: Int = 1,
    val correct: Int = 0,
    val total: Int = 0,
    val passed: Boolean = false,
)

@Serializable
data class PlacementResult(
    @SerializedName("start_week")
    @SerialName("start_week")
    val startWeek: Int = 1,
    val level: String = "A1",
    @SerializedName("cleared_tier")
    @SerialName("cleared_tier")
    val clearedTier: Int = 0,
    @SerializedName("score_percent")
    @SerialName("score_percent")
    val scorePercent: Int = 0,
    @SerializedName("correct_count")
    @SerialName("correct_count")
    val correctCount: Int = 0,
    val total: Int = 0,
    val tiers: List<PlacementTierResult> = emptyList(),
    val summary: String = "",
    val state: UserProgramState? = null,
)

@Serializable
data class ProgressDay(
    val date: String = "",
    val minutes: Int = 0,
)

@Serializable
data class ProgressSummary(
    @SerializedName("today_minutes")
    @SerialName("today_minutes")
    val todayMinutes: Int = 0,
    val goal: Int = 30,
    val streak: Int = 0,
    @SerializedName("best_streak")
    @SerialName("best_streak")
    val bestStreak: Int = 0,
    val days: List<ProgressDay> = emptyList(),
    @SerializedName("current_week")
    @SerialName("current_week")
    val currentWeek: Int = 1,
    val phase: Int = 1,
)

@Serializable
data class SessionCreateResponse(val id: String = "")

@Serializable
data class SessionEndResponse(
    val id: String = "",
    @SerializedName("feedback_status")
    @SerialName("feedback_status")
    val feedbackStatus: String = "none",
)

@Serializable
data class FeedbackMistake(
    val said: String = "",
    val correct: String = "",
    val note: String = "",
    /** IPA of the corrected form when pronunciation-related. */
    val ipa: String = "",
    @SerializedName("mouth_tip")
    @SerialName("mouth_tip")
    val mouthTip: String = "",
    /** "critical" | "minor" — used by evaluateReadiness (D6). */
    val severity: String = "minor",
)

@Serializable
data class ProgramQuizQuestion(
    val question: String = "",
    val options: List<String> = emptyList(),
    /** "vocabulary" | "pronunciation" (task v3.1 dual section). */
    val section: String = "vocabulary",
)

@Serializable
data class ProgramQuizPayload(
    val week: Int = 0,
    @SerializedName("passing_score_percent")
    @SerialName("passing_score_percent")
    val passingScorePercent: Int = 70,
    val questions: List<ProgramQuizQuestion> = emptyList(),
)

@Serializable
data class QuizSubmitResult(
    @SerializedName("score_percent")
    @SerialName("score_percent")
    val scorePercent: Int = 0,
    val passed: Boolean = false,
    @SerializedName("can_advance")
    @SerialName("can_advance")
    val canAdvance: Boolean = false,
    /** True when this pass unlocked a new week. */
    val advanced: Boolean = false,
    @SerializedName("unlocked_week")
    @SerialName("unlocked_week")
    val unlockedWeek: Int = 1,
    @SerializedName("program_day")
    @SerialName("program_day")
    val programDay: Int = 1,
    @SerializedName("progress_hint")
    @SerialName("progress_hint")
    val progressHint: String = "",
    @SerializedName("passing_score_percent")
    @SerialName("passing_score_percent")
    val passingScorePercent: Int = 70,
    @SerializedName("correct_count")
    @SerialName("correct_count")
    val correctCount: Int = 0,
    val total: Int = 0,
    @SerializedName("vocabulary_score")
    @SerialName("vocabulary_score")
    val vocabularyScore: Int = 0,
    @SerializedName("pronunciation_score")
    @SerialName("pronunciation_score")
    val pronunciationScore: Int = 0,
    @SerializedName("vocabulary_total")
    @SerialName("vocabulary_total")
    val vocabularyTotal: Int = 0,
    @SerializedName("pronunciation_total")
    @SerialName("pronunciation_total")
    val pronunciationTotal: Int = 0,
) {
    fun canAdvanceLesson(): Boolean = canAdvance || passed
}

@Serializable
data class CheckpointResult(
    val ready: Boolean = false,
    val reasons: List<String> = emptyList(),
    @SerializedName("deficient_topics")
    @SerialName("deficient_topics")
    val deficientTopics: List<String>? = null,
    val state: UserProgramState? = null,
)

@Serializable
data class RecoveryPlan(
    val priority: String = "",
    @SerializedName("daily_drills")
    @SerialName("daily_drills")
    val dailyDrills: List<String> = emptyList(),
    @SerializedName("success_criteria")
    @SerialName("success_criteria")
    val successCriteria: String = "",
)

@Serializable
data class SessionFeedback(
    val strengths: List<String> = emptyList(),
    val mistakes: List<FeedbackMistake> = emptyList(),
    @SerializedName("better_phrases")
    @SerialName("better_phrases")
    val betterPhrases: List<String> = emptyList(),
    /** Feedback on schwa, linking, elision (Portuguese). */
    @SerializedName("pronunciation_focus")
    @SerialName("pronunciation_focus")
    val pronunciationFocus: String = "",
    /** Discourse / fluency / register notes for C1 path. */
    @SerializedName("discourse_focus")
    @SerialName("discourse_focus")
    val discourseFocus: String = "",
    @SerializedName("cefr_estimate")
    @SerialName("cefr_estimate")
    val cefrEstimate: String = "",
    @SerializedName("week_alignment")
    @SerialName("week_alignment")
    val weekAlignment: String = "",
    @SerializedName("recovery_plan")
    @SerialName("recovery_plan")
    val recoveryPlan: RecoveryPlan? = null,
    @SerializedName("next_focus")
    @SerialName("next_focus")
    val nextFocus: String = "",
    val motivation: String = "",
)

enum class ProgramSessionType(val apiValue: String) {
    THEMED("themed"),
    QUICK("quick"),
    CHUNKS("chunks"),
}

/** Phase display names and colors (ARGB). */
object ProgramPhaseUi {
    data class PhaseInfo(val name: String, val color: Long)

    fun info(phase: Int): PhaseInfo = when (phase) {
        1 -> PhaseInfo("Fundação", 0xFF10B981)
        2 -> PhaseInfo("Desenvolvimento", 0xFF3B82F6)
        3 -> PhaseInfo("Aprofundamento", 0xFF8B5CF6)
        4 -> PhaseInfo("Refinamento", 0xFFF59E0B)
        else -> PhaseInfo("Programa", 0xFF64748B)
    }
}

/** Fluency target = start_date + 6 months (aligned with backend promptBuilder). */
object ProgramDates {
    /**
     * Day 1 of the program = start_date (inclusive).
     * Matches backend programDayNumber().
     */
    fun programDay(startDateYmd: String, today: java.time.LocalDate = java.time.LocalDate.now()): Int {
        return try {
            val start = java.time.LocalDate.parse(startDateYmd)
            val diff = java.time.temporal.ChronoUnit.DAYS.between(start, today).toInt()
            (diff + 1).coerceAtLeast(1)
        } catch (_: Exception) {
            1
        }
    }

    fun targetDateIso(startDateYmd: String, months: Long = 6): String {
        return try {
            val start = java.time.LocalDate.parse(startDateYmd)
            start.plusMonths(months).toString()
        } catch (_: Exception) {
            java.time.LocalDate.now().plusMonths(months).toString()
        }
    }

    /** e.g. 12/01/2027 */
    fun targetDateBr(startDateYmd: String): String {
        return try {
            val d = java.time.LocalDate.parse(targetDateIso(startDateYmd))
            "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)
        } catch (_: Exception) {
            "—"
        }
    }

    fun startDateBr(startDateYmd: String): String {
        return try {
            val d = java.time.LocalDate.parse(startDateYmd)
            "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)
        } catch (_: Exception) {
            startDateYmd.ifBlank { "—" }
        }
    }
}
