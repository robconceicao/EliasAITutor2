package com.roberto.eliasaitutor.network

import com.roberto.eliasaitutor.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// 1. O formato dos dados que o Claude entende e devolve
data class ClaudeMessage(val role: String, val content: String)
data class ClaudeContent(val text: String)
data class ClaudeResponse(val content: List<ClaudeContent>)

data class ClaudeRequest(
    val model: String = "claude-3-5-sonnet-20241022",
    val max_tokens: Int = 1024,
    val system: String = "Act as an English teacher.",
    val stream: Boolean = false,
    val messages: List<ClaudeMessage>
)

// 2. A Interface de Conexão
interface AnthropicApi {
    @POST("v1/messages")
    suspend fun generateMessage(@Body request: ClaudeRequest): ClaudeResponse
    
    @retrofit2.http.Streaming
    @POST("v1/messages")
    suspend fun generateMessageStream(@Body request: ClaudeRequest): okhttp3.ResponseBody
}

// 3. O Cliente Seguro
object AnthropicClient {
    private val authInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("x-api-key", BuildConfig.CLAUDE_API_KEY)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .build()
        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val api: AnthropicApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AnthropicApi::class.java)
    }
}