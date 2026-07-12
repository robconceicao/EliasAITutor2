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
)

@Serializable
data class SessionFeedback(
    val mistakes: List<FeedbackMistake> = emptyList(),
    @SerializedName("better_phrases")
    @SerialName("better_phrases")
    val betterPhrases: List<String> = emptyList(),
    /** Feedback on schwa, linking, elision (Portuguese). */
    @SerializedName("pronunciation_focus")
    @SerialName("pronunciation_focus")
    val pronunciationFocus: String = "",
    @SerializedName("cefr_estimate")
    @SerialName("cefr_estimate")
    val cefrEstimate: String = "",
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
