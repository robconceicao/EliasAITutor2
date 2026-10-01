package com.roberto.eliasaitutor.network

import com.roberto.eliasaitutor.BuildConfig
import com.roberto.eliasaitutor.model.ErrorEntry
import com.roberto.eliasaitutor.model.SentimentEntry
import com.roberto.eliasaitutor.model.XpEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class SoftSkills(
    val confidence: Int = 50,
    val clarity: Int = 50,
    val posture: Int = 50,
    val summary: String = ""
)

@Serializable
data class SupabaseProfile(
    @SerialName("user_id") val userId: String,
    val xp: Int,
    val coins: Int,
    val level: Int,
    @SerialName("british_unlocked") val britishUnlocked: Boolean,
    @SerialName("messages_sent") val messagesSent: Int,
    @SerialName("error_log") val errorLog: List<ErrorEntry> = emptyList(),
    @SerialName("soft_skills") val softSkills: SoftSkills = SoftSkills(),
    @SerialName("sentiment_history") val sentimentHistory: List<SentimentEntry> = emptyList(),
    @SerialName("xp_history") val xpHistory: List<XpEntry> = emptyList()
)

@Serializable
data class SupabaseFlashOffer(
    @SerialName("offer_date") val offerDate: String,
    val title: String,
    val description: String,
    @SerialName("discount_pct") val discountPct: Int,
    val target: String,
    @SerialName("price_original") val priceOriginal: Int,
    @SerialName("price_final") val priceFinal: Int
)

data class CloudProfile(val profile: com.roberto.eliasaitutor.model.UserProfile, val revision: Long)

/** Operational JWT and RLS, using the same project as the login. No anonymous profile writes. */
object SupabaseManager {
    private val http = okhttp3.OkHttpClient.Builder()
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun request(owner: String, path: String, body: String? = null): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            check(BackendSession.currentOwnerId() == owner) { "Conta alterada" }
            val token = BackendSession.token()
            check(BackendSession.currentOwnerId() == owner) { "Conta alterada" }
            val builder = okhttp3.Request.Builder()
                .url(BuildConfig.TADEU_APPS_SUPABASE_URL.trimEnd('/') + "/rest/v1/" + path)
                .header("apikey", BuildConfig.TADEU_APPS_SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $token")
            if (body != null) builder
                .header("Prefer", if (path.startsWith("rpc/")) "return=representation" else "resolution=merge-duplicates,return=minimal")
                .post(body.toRequestBody("application/json".toMediaType()))
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Sincronização indisponível (HTTP ${response.code}). Dados locais preservados.")
                check(BackendSession.currentOwnerId() == owner) { "Conta alterada" }
                response.body?.string().orEmpty()
            }
        }

    suspend fun loadProfile(userId: String): CloudProfile? {
        val rows = org.json.JSONArray(request(userId, "elias_profiles?select=profile,revision&user_id=eq.$userId"))
        if (rows.length() == 0) return null
        val row = rows.getJSONObject(0)
        val profile = json.decodeFromString<com.roberto.eliasaitutor.model.UserProfile>(row.getJSONObject("profile").toString())
        check(profile.userId == userId) { "Perfil de outra conta recusado" }
        return CloudProfile(profile, row.getLong("revision"))
    }

    suspend fun saveProfile(snapshot: com.roberto.eliasaitutor.data.ProfileSyncSnapshot): Long {
        val body = org.json.JSONObject().put("p_expected_revision", snapshot.remoteRevision)
            .put("p_mutation_id", snapshot.mutationId)
            .put("p_profile", org.json.JSONObject(json.encodeToString(com.roberto.eliasaitutor.model.UserProfile.serializer(), snapshot.profile)))
        return request(snapshot.profile.userId, "rpc/save_elias_profile", body.toString()).trim().toLong()
    }

    suspend fun loadFlashOffer(userId: String, date: String): SupabaseFlashOffer? {
        val day = java.time.LocalDate.parse(date).toString()
        val rows = org.json.JSONArray(request(userId, "elias_flash_offers?select=offer&user_id=eq.$userId&offer_date=eq.$day"))
        if (rows.length() == 0) return null
        return json.decodeFromString<SupabaseFlashOffer>(rows.getJSONObject(0).getJSONObject("offer").toString())
    }

    suspend fun saveFlashOffer(userId: String, offer: SupabaseFlashOffer) {
        val body = org.json.JSONObject().put("user_id", userId).put("offer_date", offer.offerDate)
            .put("offer", org.json.JSONObject(json.encodeToString(SupabaseFlashOffer.serializer(), offer)))
        request(userId, "elias_flash_offers?on_conflict=user_id,offer_date", body.toString())
    }
}
