package com.roberto.eliasaitutor.program

import com.roberto.eliasaitutor.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

interface ProgramApi {
    @GET("program/weeks")
    suspend fun getWeeks(): List<ProgramWeek>

    @GET("program/weeks/{n}")
    suspend fun getWeek(@Path("n") n: Int): ProgramWeek

    @GET("program/state")
    suspend fun getState(): UserProgramState

    @PUT("program/state")
    suspend fun updateState(@Body body: Map<String, @JvmSuppressWildcards Any?>): UserProgramState

    @POST("sessions")
    suspend fun createSession(@Body body: Map<String, @JvmSuppressWildcards Any?>): SessionCreateResponse

    @PATCH("sessions/{id}/end")
    suspend fun endSession(
        @Path("id") id: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): SessionEndResponse

    @GET("sessions/{id}/feedback")
    suspend fun getFeedback(@Path("id") id: String): Response<SessionFeedback>

    @POST("sessions/{id}/feedback/retry")
    suspend fun retryFeedback(
        @Path("id") id: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): ResponseBody

    @GET("progress/summary")
    suspend fun getProgress(@Query("days") days: Int = 30): ProgressSummary

    @Streaming
    @GET("program/chunks/audio/{week}/{index}")
    suspend fun getChunkAudio(
        @Path("week") week: Int,
        @Path("index") index: Int,
    ): ResponseBody

    /** B.6 weekly quiz (answers stripped). */
    @GET("program/quiz/{week}")
    suspend fun getQuiz(@Path("week") week: Int): ProgramQuizPayload

    @POST("program/quiz/{week}/submit")
    suspend fun submitQuiz(
        @Path("week") week: Int,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): QuizSubmitResult

    @POST("program/checkpoint")
    suspend fun runCheckpoint(): CheckpointResult

    /** Nivelamento — define a semana inicial (o início não é fixo na Semana 1). */
    @GET("program/placement")
    suspend fun getPlacement(): PlacementPayload

    @POST("program/placement/submit")
    suspend fun submitPlacement(
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): PlacementResult

    @POST("program/placement/reset")
    suspend fun resetPlacement(): UserProgramState
}

object ProgramApiClient {
    /** D9 / A.5: program data fetches must not hang forever (default 10s). */
    private const val PROGRAM_TIMEOUT_SEC = 10L

    private val http = OkHttpClient.Builder()
        .connectTimeout(PROGRAM_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(PROGRAM_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(PROGRAM_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(PROGRAM_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String {
        val raw = BuildConfig.BACKEND_URL.trimEnd('/')
        return if (raw.endsWith("/")) raw else "$raw/"
    }

    val api: ProgramApi by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl())
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ProgramApi::class.java)
    }
}
