package com.roberto.eliasaitutor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.roberto.eliasaitutor.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "elias_profile")

class DataStoreManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        val KEY_USER_ID         = stringPreferencesKey("user_id")
        val KEY_XP              = intPreferencesKey("xp")
        val KEY_COINS           = intPreferencesKey("coins")
        val KEY_LEVEL           = intPreferencesKey("level")
        val KEY_STREAK          = intPreferencesKey("streak")
        val KEY_LAST_ACTIVE     = stringPreferencesKey("last_active")
        val KEY_STREAK_FREEZE   = intPreferencesKey("streak_freeze")
        val KEY_BRITISH         = booleanPreferencesKey("british_unlocked")
        val KEY_MSG_COUNT       = intPreferencesKey("messages_count")
        val KEY_CONFIDENCE      = intPreferencesKey("confidence")
        val KEY_CLARITY         = intPreferencesKey("clarity")
        val KEY_POSTURE         = intPreferencesKey("posture")
        val KEY_SS_SUMMARY      = stringPreferencesKey("ss_summary")
        val KEY_ERROR_LOG       = stringPreferencesKey("error_log_json")
        val KEY_XP_HISTORY      = stringPreferencesKey("xp_history_json")
        val KEY_SENTIMENT_HIST  = stringPreferencesKey("sentiment_history_json")
        val KEY_UNLOCKED_SCN    = stringPreferencesKey("unlocked_scenarios_json")
        val KEY_FLASH_OFFER     = stringPreferencesKey("flash_offer_json")
        val KEY_FLASH_DATE      = stringPreferencesKey("flash_offer_date")
    }

    val profileFlow: Flow<UserProfile> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val userId = prefs[KEY_USER_ID] ?: ""
            val errorLog = prefs[KEY_ERROR_LOG]?.let {
                runCatching { json.decodeFromString<List<ErrorEntry>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val xpHistory = prefs[KEY_XP_HISTORY]?.let {
                runCatching { json.decodeFromString<List<XpEntry>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val sentHist = prefs[KEY_SENTIMENT_HIST]?.let {
                runCatching { json.decodeFromString<List<SentimentEntry>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val unlockedScn = prefs[KEY_UNLOCKED_SCN]?.let {
                runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()

            UserProfile(
                userId           = userId,
                xp               = prefs[KEY_XP]           ?: 0,
                coins            = prefs[KEY_COINS]         ?: 0,
                level            = prefs[KEY_LEVEL]         ?: 1,
                streak           = prefs[KEY_STREAK]        ?: 0,
                lastActiveDate   = prefs[KEY_LAST_ACTIVE]   ?: "",
                streakFreezeCount= prefs[KEY_STREAK_FREEZE] ?: 0,
                britishUnlocked  = prefs[KEY_BRITISH]       ?: false,
                messagesCount    = prefs[KEY_MSG_COUNT]     ?: 0,
                confidence       = prefs[KEY_CONFIDENCE]    ?: 50,
                clarity          = prefs[KEY_CLARITY]       ?: 50,
                posture          = prefs[KEY_POSTURE]       ?: 50,
                softSkillsSummary= prefs[KEY_SS_SUMMARY]    ?: "",
                errorLog         = errorLog,
                xpHistory        = xpHistory,
                sentimentHistory = sentHist,
                unlockedScenarios= unlockedScn,
            )
        }

    suspend fun save(profile: UserProfile) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_ID]      = profile.userId
            prefs[KEY_XP]           = profile.xp
            prefs[KEY_COINS]        = profile.coins
            prefs[KEY_LEVEL]        = profile.level
            prefs[KEY_STREAK]       = profile.streak
            prefs[KEY_LAST_ACTIVE]  = profile.lastActiveDate
            prefs[KEY_STREAK_FREEZE]= profile.streakFreezeCount
            prefs[KEY_BRITISH]      = profile.britishUnlocked
            prefs[KEY_MSG_COUNT]    = profile.messagesCount
            prefs[KEY_CONFIDENCE]   = profile.confidence
            prefs[KEY_CLARITY]      = profile.clarity
            prefs[KEY_POSTURE]      = profile.posture
            prefs[KEY_SS_SUMMARY]   = profile.softSkillsSummary
            prefs[KEY_ERROR_LOG]    = json.encodeToString(profile.errorLog.takeLast(100))
            prefs[KEY_XP_HISTORY]   = json.encodeToString(profile.xpHistory.takeLast(200))
            prefs[KEY_SENTIMENT_HIST] = json.encodeToString(profile.sentimentHistory.takeLast(50))
            prefs[KEY_UNLOCKED_SCN] = json.encodeToString(profile.unlockedScenarios)
        }
    }

    suspend fun saveFlashOffer(offerJson: String, date: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FLASH_OFFER] = offerJson
            prefs[KEY_FLASH_DATE]  = date
        }
    }

    suspend fun loadFlashOffer(): Pair<String, String> {
        val prefs = context.dataStore.data.catch { emit(emptyPreferences()) }.first()
        val offer = prefs[KEY_FLASH_OFFER] ?: ""
        val date  = prefs[KEY_FLASH_DATE]  ?: ""
        return offer to date
    }
}
