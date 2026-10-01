package com.roberto.eliasaitutor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import com.roberto.eliasaitutor.network.BackendSession
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import com.roberto.eliasaitutor.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private object ProfileStores {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
    @Synchronized
    fun forOwner(context: Context, owner: String): DataStore<Preferences> {
        val name = "elias_profile_" + MessageDigest.getInstance("SHA-256")
            .digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
        return stores.getOrPut(name) {
            PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile(name) }
        }
    }
}

@kotlinx.serialization.Serializable
data class ProfileSyncSnapshot(val profile: UserProfile, val localRevision: Long, val acknowledgedRevision: Long, val remoteRevision: Long, val deviceId: String) {
    val dirty: Boolean get() = localRevision > acknowledgedRevision
    val mutationId: String get() = java.util.UUID.nameUUIDFromBytes("$deviceId:$localRevision".toByteArray()).toString()
}

class DataStoreManager internal constructor(private val ownerId: String, private val dataStore: DataStore<Preferences>) {
    constructor(context: Context, owner: String = BackendSession.currentOwnerId()) : this(owner, ProfileStores.forOwner(context, owner))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        val KEY_PENDING_UPLOAD = stringPreferencesKey("profile_pending_upload")
        val KEY_LOCAL_REVISION = longPreferencesKey("profile_local_revision")
        val KEY_ACK_REVISION = longPreferencesKey("profile_ack_revision")
        val KEY_REMOTE_REVISION = longPreferencesKey("profile_remote_revision")
        val KEY_DEVICE_ID = stringPreferencesKey("profile_sync_device")
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

    private fun decodeProfile(prefs: Preferences): UserProfile {
            val userId = ownerId
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

            return UserProfile(
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
    val profileFlow: Flow<UserProfile> = dataStore.data.map(::decodeProfile).distinctUntilChanged()

    private fun localRevision(prefs: Preferences): Long = prefs[KEY_LOCAL_REVISION] ?: if (prefs.contains(KEY_USER_ID)) 1L else 0L

    suspend fun syncSnapshot(): ProfileSyncSnapshot {
        val prefs = dataStore.edit { if (!it.contains(KEY_DEVICE_ID)) it[KEY_DEVICE_ID] = java.util.UUID.randomUUID().toString() }
        return ProfileSyncSnapshot(decodeProfile(prefs), localRevision(prefs), prefs[KEY_ACK_REVISION] ?: 0L, prefs[KEY_REMOTE_REVISION] ?: 0L, prefs[KEY_DEVICE_ID]!!)
    }

    /** Persist the exact in-flight payload before POST, even if newer local edits arrive. */
    suspend fun beginUpload(): ProfileSyncSnapshot? {
        var pending: ProfileSyncSnapshot? = null
        dataStore.edit { prefs ->
            val encoded = prefs[KEY_PENDING_UPLOAD]
            if (encoded != null) {
                pending = json.decodeFromString<ProfileSyncSnapshot>(encoded)
                require(pending!!.profile.userId == ownerId) { "Operação de outra conta recusada" }
            } else if (localRevision(prefs) > (prefs[KEY_ACK_REVISION] ?: 0L)) {
                val device = prefs[KEY_DEVICE_ID] ?: java.util.UUID.randomUUID().toString().also { prefs[KEY_DEVICE_ID] = it }
                pending = ProfileSyncSnapshot(decodeProfile(prefs), localRevision(prefs), prefs[KEY_ACK_REVISION] ?: 0L, prefs[KEY_REMOTE_REVISION] ?: 0L, device)
                prefs[KEY_PENDING_UPLOAD] = json.encodeToString(pending!!)
            }
        }
        return pending
    }

    suspend fun acknowledgeUpload(upload: ProfileSyncSnapshot, remoteRevision: Long) {
        require(remoteRevision == upload.remoteRevision + 1) { "Revisão remota inválida" }
        dataStore.edit { prefs ->
            val pending = prefs[KEY_PENDING_UPLOAD]?.let { json.decodeFromString<ProfileSyncSnapshot>(it) }
            require(pending == upload && upload.profile.userId == ownerId) { "Confirmação de operação divergente" }
            prefs[KEY_ACK_REVISION] = upload.localRevision
            prefs[KEY_REMOTE_REVISION] = remoteRevision
            prefs.remove(KEY_PENDING_UPLOAD)
        }
    }

    suspend fun acceptRemote(profile: UserProfile, remoteRevision: Long, expectedLocalRevision: Long): Boolean {
        var accepted = false
        dataStore.edit { prefs ->
            if (localRevision(prefs) == expectedLocalRevision && localRevision(prefs) == (prefs[KEY_ACK_REVISION] ?: 0L)) {
                writeFields(prefs, profile)
                prefs[KEY_LOCAL_REVISION] = expectedLocalRevision
                prefs[KEY_ACK_REVISION] = expectedLocalRevision
                prefs[KEY_REMOTE_REVISION] = remoteRevision
                accepted = true
            }
        }
        return accepted
    }

    private fun writeFields(prefs: MutablePreferences, profile: UserProfile) {
        require(profile.userId == ownerId) { "Perfil de outra conta recusado" }
            prefs[KEY_USER_ID]      = ownerId
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

    suspend fun save(profile: UserProfile) {
        dataStore.edit { prefs ->
            val revision = Math.addExact(localRevision(prefs), 1L)
            writeFields(prefs, profile)
            prefs[KEY_LOCAL_REVISION] = revision
        }
    }

    suspend fun saveFlashOffer(offerJson: String, date: String) {
        dataStore.edit { prefs ->
            prefs[KEY_FLASH_OFFER] = offerJson
            prefs[KEY_FLASH_DATE]  = date
        }
    }

    suspend fun loadFlashOffer(): Pair<String, String> {
        val prefs = dataStore.data.catch { emit(emptyPreferences()) }.first()
        val offer = prefs[KEY_FLASH_OFFER] ?: ""
        val date  = prefs[KEY_FLASH_DATE]  ?: ""
        return offer to date
    }
}
