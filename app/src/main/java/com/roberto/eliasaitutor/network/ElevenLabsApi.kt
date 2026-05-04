package com.roberto.eliasaitutor.network

import com.roberto.eliasaitutor.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

data class VoiceSettings(
    val stability: Double = 0.55,
    val similarity_boost: Double = 0.80,
    val style: Double = 0.15,
    val use_speaker_boost: Boolean = true
)

data class TTSRequest(
    val text: String,
    val model_id: String = "eleven_turbo_v2",
    val voice_settings: VoiceSettings = VoiceSettings()
)

interface ElevenLabsApi {
    @Streaming
    @POST("v1/text-to-speech/{voiceId}")
    suspend fun textToSpeech(
        @Path("voiceId") voiceId: String,
        @Body request: TTSRequest
    ): ResponseBody
}

object ElevenLabsClient {
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
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
}
