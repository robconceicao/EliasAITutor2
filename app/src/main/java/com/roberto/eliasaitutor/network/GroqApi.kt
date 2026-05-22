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

import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

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
        @Part model: MultipartBody.Part,
        @Part prompt: MultipartBody.Part? = null,
        @Part responseFormat: MultipartBody.Part? = null,
        @Part temperature: MultipartBody.Part? = null,
        @Part language: MultipartBody.Part? = null
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
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val api: GroqApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/openai/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApi::class.java)
    }

    suspend fun transcribe(file: java.io.File, promptText: String? = null): String {
        val requestFile = file.asRequestBody("audio/mp4".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
        val model = MultipartBody.Part.createFormData("model", "whisper-large-v3")
        
        val finalPrompt = promptText ?: "Transcreva o português corretamente (ex: 'Não entendi'). Se o usuário falar inglês, mantenha os erros fonéticos exatamente como ouvidos (ex: 'pom' em vez de 'from'). DO NOT AUTO-CORRECT."
        val promptPart = MultipartBody.Part.createFormData("prompt", finalPrompt)
        val formatPart = MultipartBody.Part.createFormData("response_format", "json")
        val tempPart   = MultipartBody.Part.createFormData("temperature", "0.2")
        
        return try {
            val response = api.transcribeAudio(body, model, promptPart, formatPart, tempPart)
            response.text
        } catch (e: retrofit2.HttpException) {
            "Error: HTTP ${e.code()} - ${e.response()?.errorBody()?.string()}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}