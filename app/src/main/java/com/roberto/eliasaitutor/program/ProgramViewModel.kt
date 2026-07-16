package com.roberto.eliasaitutor.program

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate

/** D9 / A.5: max wait for program network refresh before error + retry. */
private const val PROGRAM_REFRESH_TIMEOUT_MS = 10_000L

data class ProgramUiState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val onboarded: Boolean = false,
    val state: UserProgramState = UserProgramState(),
    val week: ProgramWeek? = null,
    val progress: ProgressSummary = ProgressSummary(),
    val error: String? = null,
)

data class ActivePracticeSession(
    val sessionId: String,
    val week: Int,
    val type: ProgramSessionType,
    val goalMinutes: Int,
    val startedEpochMs: Long = System.currentTimeMillis(),
    val elapsedSeconds: Int = 0,
    val goalReachedNotified: Boolean = false,
    val paused: Boolean = false,
)

data class ChunksDrillState(
    val week: Int = 1,
    val chunks: List<ProgramChunk> = emptyList(),
    val index: Int = 0,
    val sessionId: String? = null,
    val startedAt: Long = 0L,
    val done: Boolean = false,
)

class ProgramViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProgramRepository(app)

    private val _ui = MutableStateFlow(ProgramUiState())
    val ui: StateFlow<ProgramUiState> = _ui.asStateFlow()

    private val _practice = MutableStateFlow<ActivePracticeSession?>(null)
    val practice: StateFlow<ActivePracticeSession?> = _practice.asStateFlow()

    private val _drill = MutableStateFlow<ChunksDrillState?>(null)
    val drill: StateFlow<ChunksDrillState?> = _drill.asStateFlow()

    private val _feedback = MutableStateFlow<SessionFeedback?>(null)
    val feedback: StateFlow<SessionFeedback?> = _feedback.asStateFlow()

    private val _feedbackStatus = MutableStateFlow("none")
    val feedbackStatus: StateFlow<String> = _feedbackStatus.asStateFlow()

    /** Weekly program quiz (F9 / B.6) — not the free-chat vocab quiz. */
    private val _weekQuiz = MutableStateFlow<ProgramQuizPayload?>(null)
    val weekQuiz: StateFlow<ProgramQuizPayload?> = _weekQuiz.asStateFlow()

    private val _quizResult = MutableStateFlow<QuizSubmitResult?>(null)
    val quizResult: StateFlow<QuizSubmitResult?> = _quizResult.asStateFlow()

    private val _quizLoading = MutableStateFlow(false)
    val quizLoading: StateFlow<Boolean> = _quizLoading.asStateFlow()

    private val _checkpointMsg = MutableStateFlow<String?>(null)
    val checkpointMsg: StateFlow<String?> = _checkpointMsg.asStateFlow()

    private var timerJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var lastTranscript: String = ""

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val onboarded = repo.isOnboarded.first()
            val localState = repo.cachedState.first()
            val localWeek = repo.getCachedWeeks().find { it.week == localState.currentWeek }

            // A.5: hard cap — never leave "Carregando…" forever
            val net = withTimeoutOrNull(PROGRAM_REFRESH_TIMEOUT_MS) {
                repo.refreshFromNetwork()
            }

            if (net == null) {
                _ui.value = ProgramUiState(
                    loading = false,
                    offline = true,
                    onboarded = onboarded,
                    state = localState,
                    week = localWeek,
                    progress = ProgressSummary(
                        todayMinutes = 0,
                        goal = localState.dailyGoalMinutes,
                        currentWeek = localState.currentWeek,
                    ),
                    error = "Tempo esgotado ao carregar o programa. Toque em Tentar novamente.",
                )
                return@launch
            }

            if (net.isSuccess) {
                val (state, weeks) = net.getOrThrow()
                val week = weeks.find { it.week == state.currentWeek }
                val progress = withTimeoutOrNull(PROGRAM_REFRESH_TIMEOUT_MS) {
                    repo.getProgress()
                } ?: ProgressSummary(
                    todayMinutes = 0,
                    goal = state.dailyGoalMinutes,
                    currentWeek = state.currentWeek,
                )
                _ui.value = ProgramUiState(
                    loading = false,
                    offline = false,
                    onboarded = true,
                    state = state,
                    week = week,
                    progress = progress,
                )
                scheduleReminderIfNeeded(state, progress)
            } else {
                _ui.value = ProgramUiState(
                    loading = false,
                    offline = true,
                    onboarded = onboarded,
                    state = localState,
                    week = localWeek,
                    progress = ProgressSummary(
                        todayMinutes = 0,
                        goal = localState.dailyGoalMinutes,
                        currentWeek = localState.currentWeek,
                    ),
                    error = "Backend indisponível — dados em cache. Toque em Tentar novamente.",
                )
            }
        }
    }

    fun completeOnboarding(startDate: String = LocalDate.now().toString(), weekMode: String = "auto") {
        viewModelScope.launch {
            repo.completeOnboarding(startDate, weekMode)
            refresh()
        }
    }

    fun shiftWeek(delta: Int) {
        viewModelScope.launch {
            val s = _ui.value.state
            if (s.weekMode != "manual") return@launch
            val next = (s.currentWeek + delta).coerceIn(1, 26)
            repo.updateState(mapOf("current_week" to next, "week_mode" to "manual"))
            refresh()
        }
    }

    fun setWeekMode(mode: String) {
        viewModelScope.launch {
            repo.updateState(mapOf("week_mode" to mode))
            refresh()
        }
    }

    fun setReminderTime(hhmm: String?) {
        viewModelScope.launch {
            repo.updateState(mapOf("reminder_time" to hhmm))
            refresh()
        }
    }

    fun setDailyGoal(minutes: Int) {
        viewModelScope.launch {
            repo.updateState(mapOf("daily_goal_minutes" to minutes.coerceAtLeast(5)))
            refresh()
        }
    }

    fun setStartDate(date: String) {
        viewModelScope.launch {
            repo.updateState(mapOf("start_date" to date))
            refresh()
        }
    }

    fun loadWeekQuiz(week: Int = _ui.value.state.currentWeek) {
        viewModelScope.launch {
            _quizLoading.value = true
            _quizResult.value = null
            _weekQuiz.value = null
            val q = repo.getQuiz(week)
            _weekQuiz.value = q
            _quizLoading.value = false
            if (q == null) {
                _ui.value = _ui.value.copy(
                    error = "Não foi possível carregar o quiz da semana $week. Tente novamente."
                )
            }
        }
    }

    fun clearWeekQuiz() {
        _weekQuiz.value = null
        _quizResult.value = null
    }

    fun submitWeekQuiz(answers: List<Int>) {
        viewModelScope.launch {
            val week = _weekQuiz.value?.week ?: _ui.value.state.currentWeek
            _quizLoading.value = true
            val result = repo.submitQuiz(week, answers)
            _quizResult.value = result
            _quizLoading.value = false
            if (result == null) {
                _ui.value = _ui.value.copy(
                    error = "Falha ao enviar o quiz. Verifique a conexão e tente de novo."
                )
            }
        }
    }

    /** Weekly readiness gate — sets held_back / clears review on server. */
    fun runCheckpoint() {
        viewModelScope.launch {
            _quizLoading.value = true
            _checkpointMsg.value = null
            val result = repo.runCheckpoint()
            _quizLoading.value = false
            if (result == null) {
                _checkpointMsg.value = "Checkpoint indisponível. Tente novamente."
                return@launch
            }
            _checkpointMsg.value = if (result.ready) {
                "Pronto para avançar! O calendário segue normalmente."
            } else {
                "Revisão necessária: ${result.reasons.joinToString(" · ").ifBlank { "veja os tópicos abaixo" }}"
            }
            // Refresh home so held_back card appears
            refresh()
        }
    }

    fun clearCheckpointMsg() {
        _checkpointMsg.value = null
    }

    /**
     * Start themed or quick conversation session (F3+F4).
     * Socket/prompt binding is done by EliasViewModel.beginProgramSession via onReady.
     */
    fun startConversationSession(
        type: ProgramSessionType,
        goalMinutes: Int,
        onReady: (week: Int, title: String, lexis: String, grammar: String, phase: Int, level: String) -> Unit,
    ) {
        viewModelScope.launch {
            val week = _ui.value.state.currentWeek
            val weekDoc = _ui.value.week
            val phase = weekDoc?.phase ?: when {
                week <= 6 -> 1
                week <= 13 -> 2
                week <= 20 -> 3
                else -> 4
            }
            // A.1: CEFR always from program_weeks.level — never user self-report
            val level = weekDoc?.level.orEmpty()
            val id = repo.createSession(week, type) ?: "local-${System.currentTimeMillis()}"
            _practice.value = ActivePracticeSession(
                sessionId = id,
                week = week,
                type = type,
                goalMinutes = goalMinutes,
            )
            _feedback.value = null
            _feedbackStatus.value = "none"
            startTimer()
            onReady(
                week,
                weekDoc?.title ?: "Semana $week",
                weekDoc?.lexis ?: "",
                weekDoc?.grammar ?: "",
                phase,
                level,
            )
        }
    }

    fun onAppForeground() {
        val p = _practice.value ?: return
        if (p.paused) {
            _practice.value = p.copy(paused = false)
            startTimer()
        }
    }

    fun onAppBackground() {
        val p = _practice.value ?: return
        if (!p.paused) {
            _practice.value = p.copy(paused = true)
            timerJob?.cancel()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val p = _practice.value ?: break
                if (p.paused) continue
                val next = p.copy(elapsedSeconds = p.elapsedSeconds + 1)
                val goalSec = p.goalMinutes * 60
                if (!next.goalReachedNotified && next.elapsedSeconds >= goalSec) {
                    _practice.value = next.copy(goalReachedNotified = true)
                    // toast via callback state — UI observes goalReachedNotified
                } else {
                    _practice.value = next
                }
            }
        }
    }

    fun endConversationSession(transcript: String = "", onEnded: () -> Unit = {}) {
        viewModelScope.launch {
            timerJob?.cancel()
            val p = _practice.value ?: return@launch
            lastTranscript = transcript
            // Only request LLM report for sessions ≥10 min (prompt rule)
            val resp = if (p.elapsedSeconds >= 10 * 60) {
                repo.endSession(p.sessionId, p.elapsedSeconds, transcript)
            } else {
                repo.endSession(p.sessionId, p.elapsedSeconds, "")
            }
            repo.addLocalPracticeMinutes(p.elapsedSeconds / 60)
            _feedbackStatus.value = if (p.elapsedSeconds >= 10 * 60) {
                resp?.feedbackStatus ?: "none"
            } else {
                "none"
            }
            _practice.value = null
            onEnded()
            if (_feedbackStatus.value == "pending") {
                pollFeedback(p.sessionId)
            }
            refresh()
        }
    }

    private fun pollFeedback(sessionId: String) {
        viewModelScope.launch {
            repeat(20) {
                delay(1500)
                val fb = repo.getFeedback(sessionId)
                if (fb != null) {
                    _feedback.value = fb
                    _feedbackStatus.value = "ready"
                    return@launch
                }
            }
            _feedbackStatus.value = "failed"
        }
    }

    fun clearFeedback() {
        _feedback.value = null
        _feedbackStatus.value = "none"
    }

    // ── Chunks drill (F7) ───────────────────────────────────

    fun startChunksDrill() {
        viewModelScope.launch {
            val weekNum = _ui.value.state.currentWeek
            val week = repo.getWeek(weekNum) ?: _ui.value.week ?: return@launch
            val id = repo.createSession(weekNum, ProgramSessionType.CHUNKS)
            _drill.value = ChunksDrillState(
                week = weekNum,
                chunks = week.chunks,
                index = 0,
                sessionId = id,
                startedAt = System.currentTimeMillis(),
            )
        }
    }

    fun playCurrentChunk() {
        val d = _drill.value ?: return
        val chunk = d.chunks.getOrNull(d.index) ?: return
        viewModelScope.launch {
            try {
                mediaPlayer?.release()
                // Prefer cached audio from backend
                val body = runCatching {
                    ProgramApiClient.api.getChunkAudio(d.week, d.index)
                }.getOrNull()
                if (body != null) {
                    val tmp = File(getApplication<Application>().cacheDir, "chunk_play.mp3")
                    tmp.writeBytes(body.bytes())
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tmp.absolutePath)
                        prepare()
                        start()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ProgramVM", "chunk audio: ${e.message}")
            }
        }
    }

    fun nextChunk() {
        val d = _drill.value ?: return
        if (d.index + 1 >= d.chunks.size) {
            finishDrill()
        } else {
            _drill.value = d.copy(index = d.index + 1)
        }
    }

    fun finishDrill() {
        viewModelScope.launch {
            val d = _drill.value ?: return@launch
            val duration = ((System.currentTimeMillis() - d.startedAt) / 1000).toInt().coerceAtLeast(1)
            d.sessionId?.let { repo.endSession(it, duration) }
            repo.addLocalPracticeMinutes(duration / 60)
            _drill.value = d.copy(done = true)
            refresh()
        }
    }

    fun closeDrill() {
        mediaPlayer?.release()
        mediaPlayer = null
        _drill.value = null
    }

    private fun scheduleReminderIfNeeded(state: UserProgramState, progress: ProgressSummary) {
        val time = state.reminderTime ?: return
        PracticeReminderScheduler.schedule(
            getApplication(),
            time,
            state.currentWeek,
            _ui.value.week?.title ?: "",
            skipIfGoalMet = progress.todayMinutes >= state.dailyGoalMinutes,
        )
    }

    override fun onCleared() {
        timerJob?.cancel()
        mediaPlayer?.release()
        super.onCleared()
    }

    companion object {
        fun Factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return ProgramViewModel(app) as T
                }
            }
    }
}
