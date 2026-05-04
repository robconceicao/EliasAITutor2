package com.roberto.eliasaitutor.network

import com.roberto.eliasaitutor.BuildConfig
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

// 1. Modelos de Dados
data class GroqMessage(val role: String, val content: String)

data class GroqRequest(
    val model: String = "llama-3.1-8b-instant",
    val messages: List<GroqMessage>
)

data class GroqChoice(val message: GroqMessage)
data class GroqResponse(val choices: List<GroqChoice>)

data class GroqTranscriptionResponse(val text: String)

// 2. A Interface (A Tomada - onde as rotas ficam)
interface GroqApi {
    @POST("v1/chat/completions")
    suspend fun generateChat(@Body request: GroqRequest): GroqResponse

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Part file: MultipartBody.Part,
        @Part model: MultipartBody.Part
    ): GroqTranscriptionResponse
}

// 3. O Cliente (O Motor - onde a conexão é criada)
object GroqClient {
    private val authInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
            .build()
        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .build()

    val api: GroqApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/openai/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApi::class.java)
    }
}