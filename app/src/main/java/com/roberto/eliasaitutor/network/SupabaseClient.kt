package com.roberto.eliasaitutor.network

import com.roberto.eliasaitutor.BuildConfig
import com.roberto.eliasaitutor.model.ErrorEntry
import com.roberto.eliasaitutor.model.SentimentEntry
import com.roberto.eliasaitutor.model.XpEntry
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

object SupabaseManager {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_KEY
    ) {
        install(Postgrest)
    }

    suspend fun loadProfile(userId: String): SupabaseProfile? {
        return try {
            client.postgrest["profiles"]
                .select { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<SupabaseProfile>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun upsertProfile(profile: SupabaseProfile) {
        try {
            client.postgrest["profiles"].upsert(profile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun loadFlashOffer(date: String): SupabaseFlashOffer? {
        return try {
            client.postgrest["flash_offers"]
                .select { filter { eq("offer_date", date) } }
                .decodeSingleOrNull<SupabaseFlashOffer>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveFlashOffer(offer: SupabaseFlashOffer) {
        try {
            client.postgrest["flash_offers"].upsert(offer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
