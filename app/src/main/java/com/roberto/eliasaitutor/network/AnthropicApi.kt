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
    val model: String = "claude-3-5-sonnet-latest", // Puxa sempre a versão mais nova e ativa!
    val max_tokens: Int = 1024,
    val system: String = "Act as an English teacher. Say a very short, one-sentence motivational quote in English.",
    val messages: List<ClaudeMessage>
)

// 2. A Interface de Conexão
interface AnthropicApi {
    @POST("v1/messages")
    suspend fun generateMessage(@Body request: ClaudeRequest): ClaudeResponse
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