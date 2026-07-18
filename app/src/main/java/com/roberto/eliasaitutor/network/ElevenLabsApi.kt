package com.roberto.eliasaitutor.network

import com.roberto.eliasaitutor.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

data class VoiceSettings(
    val stability: Double = 0.5,
    val similarity_boost: Double = 0.8,
    val style: Double = 0.1,
    val use_speaker_boost: Boolean = true,
)

data class TTSRequest(
    val text: String,
    /** Low-latency model — must match backend STREAM_MODEL_ID. */
    val model_id: String = "eleven_flash_v2_5",
    val voice_settings: VoiceSettings = VoiceSettings(),
)

interface ElevenLabsApi {
    @Streaming
    @POST("v1/text-to-speech/{voiceId}")
    suspend fun textToSpeech(
        @Path("voiceId") voiceId: String,
        @Query("output_format") outputFormat: String = "mp3_44100_128",
        @Body request: TTSRequest,
    ): ResponseBody
}

/**
 * Client-side ElevenLabs REST TTS (emergency fallback).
 * Primary path remains backend stream-input → Opus → OpusAudioPlayer.
 * Used when backend reports elevenlabs_api_key_missing / voice_open_failed
 * or when speakText times out without audio.
 */
object ElevenLabsClient {
    /** Default voice for residual client-side TTS (never Adam). Override via ELEVENLABS_VOICE_ID. */
    val defaultVoiceId: String
        get() = BuildConfig.ELEVENLABS_VOICE_ID.ifBlank { "TX3LPaxmHKxFdv7VOQHJ" }

    val hasApiKey: Boolean
        get() = BuildConfig.ELEVENLABS_API_KEY.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                .addHeader("Accept", "audio/mpeg")
                .build()
            chain.proceed(request)
        }
        .build()

    val api: ElevenLabsApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.elevenlabs.io/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ElevenLabsApi::class.java)
    }

    suspend fun textToSpeech(text: String, voiceId: String = defaultVoiceId): ResponseBody {
        val trimmed = text.trim().take(2500)
        require(trimmed.isNotEmpty()) { "empty TTS text" }
        require(hasApiKey) { "ELEVENLABS_API_KEY missing on client" }
        return api.textToSpeech(
            voiceId = voiceId,
            outputFormat = "mp3_44100_128",
            request = TTSRequest(text = trimmed),
        )
    }
}
