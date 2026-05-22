package com.roberto.eliasaitutor.network

import com.roberto.eliasaitutor.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// Data classes (OpenAI-compatible format)
data class DSMessage(val role: String, val content: String)
data class DSRequest(
    val model: String = "deepseek-chat",
    val messages: List<DSMessage>,
    val max_tokens: Int = 400,
    val temperature: Double = 0.7,
)
data class DSChoice(val message: DSMessage)
data class DSResponse(val choices: List<DSChoice>)

interface DeepSeekApi {
    @POST("v1/chat/completions")
    suspend fun chat(@Body request: DSRequest): DSResponse
}

object DeepSeekClient {
    private val authInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${BuildConfig.DEEPSEEK_API_KEY}")
            .build()
        chain.proceed(req)
    }

    val api: DeepSeekApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeepSeekApi::class.java)
    }
}
