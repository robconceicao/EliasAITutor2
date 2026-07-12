package com.roberto.eliasaitutor.program

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val Context.programDataStore by preferencesDataStore(name = "elias_program")

/**
 * Cache-first program repository (F2 offline home banner).
 * Backend is source of truth for curriculum; local cache for home when offline.
 */
class ProgramRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val api get() = ProgramApiClient.api

    private object Keys {
        val START_DATE = stringPreferencesKey("start_date")
        val CURRENT_WEEK = intPreferencesKey("current_week")
        val WEEK_MODE = stringPreferencesKey("week_mode")
        val REMINDER = stringPreferencesKey("reminder_time")
        val GOAL = intPreferencesKey("daily_goal_minutes")
        val WEEKS_JSON = stringPreferencesKey("weeks_json")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val TODAY_MINUTES = intPreferencesKey("today_minutes")
        val TODAY_DATE = stringPreferencesKey("today_date")
        val STREAK = intPreferencesKey("streak")
    }

    val cachedState: Flow<UserProgramState> = context.programDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            UserProgramState(
                startDate = p[Keys.START_DATE] ?: LocalDate.now().toString(),
                currentWeek = p[Keys.CURRENT_WEEK] ?: 1,
                weekMode = p[Keys.WEEK_MODE] ?: "auto",
                reminderTime = p[Keys.REMINDER],
                dailyGoalMinutes = p[Keys.GOAL] ?: 30,
            ).let { resolveWeekLocally(it) }
        }

    val isOnboarded: Flow<Boolean> = context.programDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { it[Keys.ONBOARDED] ?: false }

    suspend fun getCachedWeeks(): List<ProgramWeek> {
        val raw = context.programDataStore.data.first()[Keys.WEEKS_JSON] ?: return emptyList()
        return runCatching { json.decodeFromString<List<ProgramWeek>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun refreshFromNetwork(): Result<Pair<UserProgramState, List<ProgramWeek>>> {
        return runCatching {
            val state = api.getState()
            val weeks = api.getWeeks()
            persistState(state)
            context.programDataStore.edit {
                it[Keys.WEEKS_JSON] = json.encodeToString(weeks)
                it[Keys.ONBOARDED] = true
            }
            resolveWeekLocally(state) to weeks
        }
    }

    suspend fun getWeek(n: Int): ProgramWeek? {
        return runCatching { api.getWeek(n) }.getOrNull()
            ?: getCachedWeeks().find { it.week == n }
    }

    suspend fun updateState(patch: Map<String, Any?>): Result<UserProgramState> {
        return runCatching {
            val state = api.updateState(patch)
            persistState(state)
            resolveWeekLocally(state)
        }.recoverCatching {
            // Offline fallback: apply patch locally
            val current = cachedState.first()
            val next = current.copy(
                startDate = (patch["start_date"] as? String) ?: current.startDate,
                currentWeek = (patch["current_week"] as? Number)?.toInt() ?: current.currentWeek,
                weekMode = (patch["week_mode"] as? String) ?: current.weekMode,
                reminderTime = if (patch.containsKey("reminder_time")) patch["reminder_time"] as? String else current.reminderTime,
                dailyGoalMinutes = (patch["daily_goal_minutes"] as? Number)?.toInt()
                    ?: current.dailyGoalMinutes,
            )
            val resolved = resolveWeekLocally(next)
            persistState(resolved)
            resolved
        }
    }

    suspend fun completeOnboarding(startDate: String, weekMode: String): Result<UserProgramState> {
        return updateState(
            mapOf(
                "start_date" to startDate,
                "week_mode" to weekMode,
                "current_week" to 1,
                "daily_goal_minutes" to 30,
            )
        ).also {
            context.programDataStore.edit { it[Keys.ONBOARDED] = true }
        }
    }

    suspend fun createSession(week: Int, type: ProgramSessionType): String? {
        return runCatching {
            api.createSession(
                mapOf(
                    "week" to week,
                    "type" to type.apiValue,
                    "started_at" to java.time.Instant.now().toString(),
                )
            ).id
        }.getOrNull()
    }

    suspend fun endSession(
        id: String,
        durationSeconds: Int,
        transcript: String? = null,
    ): SessionEndResponse? {
        return runCatching {
            val body = mutableMapOf<String, Any?>(
                "ended_at" to java.time.Instant.now().toString(),
                "duration_seconds" to durationSeconds,
            )
            if (transcript != null) body["transcript"] = transcript
            api.endSession(id, body)
        }.getOrNull()
    }

    suspend fun getFeedback(id: String): SessionFeedback? {
        return runCatching {
            val res = api.getFeedback(id)
            if (res.code() == 202) null
            else res.body()
        }.getOrNull()
    }

    suspend fun getProgress(): ProgressSummary {
        return runCatching { api.getProgress(30) }.getOrElse {
            ProgressSummary(
                todayMinutes = context.programDataStore.data.first()[Keys.TODAY_MINUTES] ?: 0,
                goal = cachedState.first().dailyGoalMinutes,
                streak = context.programDataStore.data.first()[Keys.STREAK] ?: 0,
                currentWeek = cachedState.first().currentWeek,
            )
        }
    }

    suspend fun addLocalPracticeMinutes(minutes: Int) {
        val today = LocalDate.now().toString()
        context.programDataStore.edit { p ->
            val storedDate = p[Keys.TODAY_DATE]
            val current = if (storedDate == today) p[Keys.TODAY_MINUTES] ?: 0 else 0
            p[Keys.TODAY_DATE] = today
            p[Keys.TODAY_MINUTES] = current + minutes
        }
    }

    fun goalMetToday(state: UserProgramState, summary: ProgressSummary): Boolean {
        return summary.todayMinutes >= state.dailyGoalMinutes
    }

    private suspend fun persistState(state: UserProgramState) {
        context.programDataStore.edit {
            it[Keys.START_DATE] = state.startDate
            it[Keys.CURRENT_WEEK] = state.currentWeek
            it[Keys.WEEK_MODE] = state.weekMode
            if (state.reminderTime != null) it[Keys.REMINDER] = state.reminderTime
            else it.remove(Keys.REMINDER)
            it[Keys.GOAL] = state.dailyGoalMinutes
        }
    }

    companion object {
        /** Local device date for auto week (F2 / timezone risk mitigation). */
        fun computeAutoWeek(startDate: String, today: LocalDate = LocalDate.now()): Int {
            return try {
                val start = LocalDate.parse(startDate)
                val days = ChronoUnit.DAYS.between(start, today).toInt()
                (1 + days / 7).coerceIn(1, 26)
            } catch (_: Exception) {
                1
            }
        }

        fun resolveWeekLocally(state: UserProgramState): UserProgramState {
            return if (state.weekMode == "auto") {
                state.copy(currentWeek = computeAutoWeek(state.startDate))
            } else {
                state.copy(currentWeek = state.currentWeek.coerceIn(1, 26))
            }
        }
    }
}
