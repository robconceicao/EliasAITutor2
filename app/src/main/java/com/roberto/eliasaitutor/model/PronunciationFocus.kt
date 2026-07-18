package com.roberto.eliasaitutor.model

import java.time.LocalDate

/**
 * Daily pronunciation focus for Programa dashboard + session kickoff (Task Final).
 */
object PronunciationFocus {
    val TAGS: List<String> = listOf(
        "IPA",
        "Shadowing",
        "Schwa",
        "Linking",
        "Elisão",
        "Entonação",
    )

    fun focusOfDay(dayOfYear: Int = runCatching { LocalDate.now().dayOfYear }.getOrDefault(1)): String {
        val idx = dayOfYear.mod(TAGS.size).let { if (it < 0) it + TAGS.size else it }
        return TAGS[idx]
    }

    fun coachingTip(focus: String = focusOfDay()): String = when (focus) {
        "IPA" -> "visualize each sound; check /θ/ /ð/ /æ/ and vowel length."
        "Shadowing" -> "speak with Elias, same pace — don't wait until he finishes."
        "Schwa" -> "weaken unstressed syllables to /ə/ (about, today, support)."
        "Linking" -> "connect final consonant to next vowel (go_out, pick_it_up)."
        "Elisão" -> "drop weak sounds in fast speech (want to → wanna, going to → gonna)."
        "Entonação" -> "rise on yes/no questions; fall on statements; stress content words."
        else -> "imitate rhythm, stress, and connected speech."
    }

    /** Short line for PROGRAM session kickoff to the tutor LLM. */
    fun kickoffHint(focus: String = focusOfDay()): String =
        "TODAY'S PRONUNCIATION FOCUS: $focus — ${coachingTip(focus)} " +
            "Prioritize this focus in drills while still covering IPA + shadowing basics."
}
