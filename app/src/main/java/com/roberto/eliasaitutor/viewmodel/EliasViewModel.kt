package com.roberto.eliasaitutor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roberto.eliasaitutor.data.DataStoreManager
import com.roberto.eliasaitutor.data.GameConstants
import com.roberto.eliasaitutor.model.*
import com.roberto.eliasaitutor.network.*
import android.media.MediaPlayer
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.time.LocalDate

class EliasViewModel(app: Application) : AndroidViewModel(app) {

    private val ds = DataStoreManager(app)
    private val jsonParser = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Profile state ──────────────────────────────────────────────────────────
    val profile: StateFlow<UserProfile> = ds.profileFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserProfile())

    // ── Chat state ─────────────────────────────────────────────────────────────
    private val _chatBubbles = MutableStateFlow<List<UiChatBubble>>(emptyList())
    val chatBubbles: StateFlow<List<UiChatBubble>> = _chatBubbles

    // history sent to Claude (raw API format)
    private val claudeHistory = mutableListOf<ClaudeMessage>()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /**
     * A.5: visible when a chat/network wait timed out — UI must offer retry, never infinite spinner.
     */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage

    /** Max wait for first texto_chunk / mensagem_ia after send (A.5). TTS first-byte is 8s on backend. */
    private val responseTimeoutMs = 45_000L
    private var responseTimeoutJob: Job? = null

    /**
     * Active chat flow: FREE by default; PROGRAM when started from Programa tab.
     * Prefer [chatContext] for new code; [programChat] kept as convenience for UI.
     */
    private val _chatContext = MutableStateFlow(
        com.roberto.eliasaitutor.model.ChatContext(
            type = com.roberto.eliasaitutor.model.ChatType.FREE
        )
    )
    val chatContext: StateFlow<com.roberto.eliasaitutor.model.ChatContext> = _chatContext

    /** Non-null only in PROGRAM mode (week metadata for banners). */
    val programChat: StateFlow<com.roberto.eliasaitutor.model.ChatContext?> =
        _chatContext.map { ctx ->
            if (ctx.type == com.roberto.eliasaitutor.model.ChatType.PROGRAM) ctx else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var pendingTranslationIndex: Int = -1

    /** Clear infinite-loading guard when any LLM/TTS progress arrives. */
    private fun clearResponseTimeout() {
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
    }

    private fun armResponseTimeout() {
        clearResponseTimeout()
        _loadError.value = null
        responseTimeoutJob = viewModelScope.launch {
            delay(responseTimeoutMs)
            if (_isLoading.value) {
                _isLoading.value = false
                _loadError.value =
                    "Demorou demais para responder. Verifique a conexão e tente de novo."
                _toastMessage.value = "Tempo esgotado — tente novamente"
            }
        }
    }

    fun clearLoadError() {
        _loadError.value = null
    }

    private val bargeInController by lazy {
        com.roberto.eliasaitutor.audio.BargeInController(
            audioPlayer = opusAudioPlayer,
            onStateChange = { state ->
                // Can update UI state if needed based on BargeInController state
            }
        )
    }

    private val localVad = com.roberto.eliasaitutor.audio.LocalVAD(
        onSpeechStart = {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                bargeInController.onUserBeginsSpeech()
            }
        },
        onSpeechEnd = { finalAudio ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                bargeInController.onUserEndsSpeech()
            }
        }
    )
    private val opusAudioPlayer = com.roberto.eliasaitutor.audio.OpusAudioPlayer()
    private val audioCaptureManager = com.roberto.eliasaitutor.audio.AudioCaptureManager(
        context = app, 
        vad = localVad, 
        onAudioReady = {}, 
        enableNoiseSuppression = true
    ).apply {
        onFusedVADUpdate = { rms ->
            _userVoiceRms.value = rms
        }
    }
    private val fallbackPcmPlayer = PcmFloatPlayer()
    private val audioHelper = com.roberto.eliasaitutor.audio.AudioHelper(app)
    private var speechRecognizer: android.speech.SpeechRecognizer? = null

    private val _userVoiceRms = MutableStateFlow(0f)
    val userVoiceRms: StateFlow<Float> = _userVoiceRms

    private val _jitterStats = MutableStateFlow<com.roberto.eliasaitutor.audio.JitterStats?>(null)
    val jitterStats: StateFlow<com.roberto.eliasaitutor.audio.JitterStats?> = _jitterStats

    private var isInterrupted = false
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isIaSpeaking = MutableStateFlow(false)
    val isIaSpeaking: StateFlow<Boolean> = _isIaSpeaking

    private var streamingBubbleIndex = -1

    /**
     * When backend TTS fails (missing Render key / voice_open_failed), speak the
     * last Elias text via client ElevenLabs REST (mp3 → MediaPlayer).
     */
    @Volatile private var pendingClientTtsText: String? = null
    @Volatile private var backendTtsFailed = false
    private var clientTtsJob: Job? = null

    private fun initSpeechRecognizer() {
        try {
            val context = getApplication<Application>()
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
                android.util.Log.e("EliasViewModel", "Reconhecimento de voz não disponível neste dispositivo.")
                return
            }
            speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onRmsChanged(rmsdB: Float) {
                    // Ignoramos RMS do SpeechRecognizer, usamos do RNNoise+LocalVAD
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                
                override fun onBeginningOfSpeech() {
                    bargeInController.onUserBeginsSpeech()
                }

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    android.util.Log.e("EliasViewModel", "STT Error: $error")
                    _isRecording.value = false
                    audioCaptureManager.startCapture()
                }

                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val transcript = matches[0]
                        if (_chatBubbles.value.isEmpty()) {
                            // If first message, send normally to initialize profile
                            sendMessage(transcript)
                        } else {
                            // Append bubble locally
                            _chatBubbles.value = _chatBubbles.value + UiChatBubble(transcript, isUser = true)
                            // Send to TurnTaking engine
                            SocketClient.sendSpeechEnd(transcript, 1000L, 1.0f)
                            _isLoading.value = true
                        }
                    }
                    _isRecording.value = false
                    audioCaptureManager.startCapture()
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        } catch (e: Exception) {
            android.util.Log.e("EliasViewModel", "Falha ao inicializar SpeechRecognizer: ${e.message}")
            speechRecognizer = null
        }
    }

    fun startListening() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            audioCaptureManager.stopCapture()
            if (speechRecognizer == null) {
                initSpeechRecognizer()
            }
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.US.toString())
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            try {
                speechRecognizer?.startListening(intent)
                _isRecording.value = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopListening() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _isRecording.value = false
            audioCaptureManager.startCapture()
        }
    }

    fun startRecording(context: android.content.Context) {
        startListening()
    }

    fun stopRecording(context: android.content.Context) {
        stopListening()
    }

    private fun interruptAi() {
        if (!isInterrupted) {
            isInterrupted = true
            SocketClient.usuarioInterrompeu()
            opusAudioPlayer.stopPlayout()
            fallbackPcmPlayer.flush()
            _isLoading.value = false
        }
    }


    /**
     * Immersion / Echo / replay: backend ElevenLabs stream first (Opus path).
     * Falls back to client REST TTS when offline or backend reports voice failure.
     * [onCompletion] fires when playback ends (or after failure).
     */
    fun speakText(text: String, onCompletion: () -> Unit = {}) {
        if (text.isBlank()) {
            onCompletion()
            return
        }
        val cleaned = text.trim()
        if (!SocketClient.connectionStatus.value) {
            viewModelScope.launch {
                val ok = playClientTts(cleaned)
                if (!ok) {
                    _toastMessage.value = "Offline — conecte-se para ouvir o áudio"
                }
                onCompletion()
            }
            return
        }
        backendTtsFailed = false
        SocketClient.sendShadowSpeak(cleaned)
        viewModelScope.launch {
            val started = withTimeoutOrNull(10_000) {
                SocketClient.iaStateFlow.first { it == "falando" }
            }
            if (started == null || backendTtsFailed) {
                val ok = playClientTts(cleaned)
                if (!ok && !backendTtsFailed) {
                    _toastMessage.value = "Voz indisponível — tente de novo"
                }
                onCompletion()
                return@launch
            }
            withTimeoutOrNull(60_000) {
                SocketClient.iaStateFlow.first { it == "ociosa" }
            }
            if (backendTtsFailed) {
                playClientTts(cleaned)
            }
            onCompletion()
        }
    }

    /**
     * Emergency client-side TTS (ElevenLabs REST → mp3). Returns true if audio started.
     */
    private suspend fun playClientTts(text: String): Boolean {
        if (text.isBlank()) return false
        if (!ElevenLabsClient.hasApiKey) {
            android.util.Log.w(
                "EliasViewModel",
                "Client TTS skipped — no ELEVENLABS_API_KEY / My-English-Coach-Key in BuildConfig"
            )
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                clientTtsJob?.cancel()
                val body = ElevenLabsClient.textToSpeech(text)
                val bytes = body.bytes()
                if (bytes.size < 200) {
                    android.util.Log.e("EliasViewModel", "Client TTS empty body size=${bytes.size}")
                    return@withContext false
                }
                withContext(Dispatchers.Main) {
                    _isIaSpeaking.value = true
                    audioHelper.playAudio(bytes) {
                        _isIaSpeaking.value = false
                    }
                }
                android.util.Log.i("EliasViewModel", "Client TTS playing ${bytes.size} bytes")
                true
            } catch (e: Exception) {
                android.util.Log.e("EliasViewModel", "Client TTS failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    _isIaSpeaking.value = false
                }
                false
            }
        }
    }

    /** Speak last Elias bubble via client when backend stream failed. */
    private fun maybeClientSpeakPending() {
        val text = pendingClientTtsText?.trim().orEmpty()
        if (text.isEmpty() || !backendTtsFailed) return
        pendingClientTtsText = null
        clientTtsJob?.cancel()
        clientTtsJob = viewModelScope.launch {
            delay(200) // let any late opus frames settle
            if (_isIaSpeaking.value) return@launch
            val ok = playClientTts(text)
            if (ok) {
                _toastMessage.value = "Voz (fallback local)"
            }
        }
    }

    private val _shadowTranscript = MutableStateFlow("")
    val shadowTranscript: StateFlow<String> = _shadowTranscript

    /**
     * Echo scoring:
     * - Online: backend Whisper (Groq) + LLM / word-overlap / duration heuristic
     * - Offline: local duration heuristic
     * Awards XP/coins once per successful score.
     */
    fun submitShadowingAudio(audioFile: java.io.File, phrase: String = "") {
        viewModelScope.launch {
            _isLoading.value = true
            _shadowScore.value = null
            _shadowFeedback.value = ""
            _shadowTranscript.value = ""
            try {
                val target = phrase.ifBlank { _shadowPhrase.value }
                if (!audioFile.exists() || audioFile.length() < 400L) {
                    applyShadowScore(
                        score = 28,
                        feedback = "Gravação vazia ou muito curta. Segure o mic e fale a frase completa de uma vez.",
                        awardRewards = false,
                    )
                    return@launch
                }

                val durationMs = withContext(Dispatchers.IO) { probeAudioDurationMs(audioFile) }
                val focus = PronunciationFocus.focusOfDay()

                if (SocketClient.connectionStatus.value) {
                    val b64 = withContext(Dispatchers.IO) {
                        val bytes = audioFile.readBytes()
                        // Cap ~2MB base64 payload
                        if (bytes.size > 2_000_000) {
                            null
                        } else {
                            Base64.encodeToString(bytes, Base64.NO_WRAP)
                        }
                    }
                    if (b64 == null) {
                        applyLocalHeuristicScore(target, durationMs, focus, audioFile.length())
                        return@launch
                    }

                    val reqId = "echo_${System.currentTimeMillis()}"
                    SocketClient.requestEchoScore(
                        reference = target,
                        audioBase64 = b64,
                        mimeType = "audio/mp4",
                        durationMs = durationMs,
                        focus = focus,
                        requestId = reqId,
                    )

                    val remote = withTimeoutOrNull(45_000) {
                        SocketClient.echoScoreFlow.first {
                            it.requestId == null || it.requestId == reqId || it.requestId.isNullOrBlank()
                        }
                    }
                    if (remote != null && remote.ok) {
                        _shadowTranscript.value = remote.transcript
                        val fb = buildString {
                            append(remote.feedback.ifBlank { "Continue praticando!" })
                            if (remote.transcript.isNotBlank() &&
                                !remote.feedback.contains(remote.transcript)
                            ) {
                                append("\nVocê disse: “${remote.transcript}”")
                            }
                        }
                        applyShadowScore(
                            remote.score.coerceIn(1, 100),
                            fb,
                            awardRewards = true,
                        )
                        return@launch
                    }
                    // Fall through to local if backend timed out / failed
                }

                applyLocalHeuristicScore(target, durationMs, focus, audioFile.length())
            } catch (e: Exception) {
                applyShadowScore(
                    score = 50,
                    feedback = "Gravação recebida. Ouça o Echo e compare com Elias. " +
                        PronunciationFocus.coachingTip(),
                    awardRewards = false,
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun applyLocalHeuristicScore(
        target: String,
        durationMs: Long,
        focus: String,
        sizeBytes: Long,
    ) {
        val wordCount = target.trim()
            .split(Regex("\\s+"))
            .count { it.isNotEmpty() }
            .coerceAtLeast(1)
        val expectedMs = (wordCount * 320 + 450).coerceIn(900, 14_000)
        val ratio = if (durationMs > 0) durationMs.toDouble() / expectedMs else 0.65

        val durationScore = when {
            durationMs in 1..399 -> 32
            ratio < 0.35 -> 42
            ratio < 0.55 -> 58
            ratio <= 1.4 -> 84
            ratio <= 2.0 -> 72
            else -> 54
        }
        val sizeScore = when {
            sizeBytes < 2_500L -> 38
            sizeBytes < 10_000L -> 62
            else -> 80
        }
        var score = ((durationScore * 0.65) + (sizeScore * 0.35)).toInt()
        if (ratio in 0.7..1.35) score = (score + 6).coerceAtMost(94)
        score = score.coerceIn(20, 95)

        val tip = PronunciationFocus.coachingTip(focus)
        val headline = when {
            score >= 85 -> "Excelente ritmo e presença de voz."
            score >= 65 -> "Bom eco — continue imitando o modelo."
            else -> "Tente de novo: ouça Elias e repita em um fôlego."
        }
        val timingNote = when {
            durationMs > 0 && ratio < 0.5 -> " A gravação parece curta demais para a frase."
            durationMs > 0 && ratio > 1.85 -> " Fale um pouco mais fluido, sem alongar demais."
            else -> ""
        }
        val ipaHint = when (focus) {
            "IPA" -> " Confira /θ/ /ð/ /æ/ e vogais longas no cartão IPA."
            "Schwa" -> " Enfraqueça sílabas átonas para /ə/."
            "Linking" -> " Ligue consoante final + vogal (ex.: pick_it_up)."
            "Elisão" -> " Use reduções naturais (wanna / gonna) sem perder clareza."
            "Entonação" -> " Caia em afirmações; marque content words."
            else -> " Compare sílaba a sílaba com o modelo de Elias."
        }
        applyShadowScore(
            score = score,
            feedback = "$headline$timingNote$ipaHint Foco de hoje ($focus): $tip",
            awardRewards = true,
        )
    }

    private suspend fun applyShadowScore(score: Int, feedback: String, awardRewards: Boolean) {
        val sc = score.coerceIn(0, 100)
        _shadowScore.value = sc
        _shadowFeedback.value = feedback
        if (!awardRewards) return
        val cur = profile.first()
        ds.save(
            cur.copy(
                xp = cur.xp + GameConstants.SHADOWING_XP,
                coins = cur.coins + GameConstants.SHADOWING_COINS,
                clarity = (cur.clarity * 0.7 + sc * 0.3).toInt().coerceIn(0, 100),
            )
        )
    }

    private fun probeAudioDurationMs(file: java.io.File): Long {
        return try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            mmr.release()
            d
        } catch (_: Exception) {
            0L
        }
    }

    private var localMediaPlayer: MediaPlayer? = null

    fun playLocalFile(file: java.io.File, onCompletion: () -> Unit = {}) {
        if (!file.exists() || file.length() == 0L) {
            _toastMessage.value = "Nenhuma gravação para ouvir"
            onCompletion()
            return
        }
        try {
            localMediaPlayer?.release()
            localMediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (localMediaPlayer === it) localMediaPlayer = null
                    onCompletion()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    if (localMediaPlayer === mp) localMediaPlayer = null
                    onCompletion()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            localMediaPlayer = null
            _toastMessage.value = "Não foi possível reproduzir a gravação"
            onCompletion()
        }
    }

    // ── Scenario ───────────────────────────────────────────────────────────────
    private val _selectedScenario = MutableStateFlow("☕ Coffee Shop")
    val selectedScenario: StateFlow<String> = _selectedScenario

    // ── Shadowing ──────────────────────────────────────────────────────────────
    private val _shadowPhrase   = MutableStateFlow("")
    val shadowPhrase: StateFlow<String> = _shadowPhrase
    private val _shadowScore    = MutableStateFlow<Int?>(null)
    val shadowScore: StateFlow<Int?> = _shadowScore
    private val _shadowFeedback = MutableStateFlow("")
    val shadowFeedback: StateFlow<String> = _shadowFeedback

    // ── Quiz state ─────────────────────────────────────────────────────────────
    private val _quiz         = MutableStateFlow<QuizQuestion?>(null)
    val quiz: StateFlow<QuizQuestion?> = _quiz
    private val _quizAnswered = MutableStateFlow(false)
    val quizAnswered: StateFlow<Boolean> = _quizAnswered

    // ── Flash offer ────────────────────────────────────────────────────────────
    private val _flashOffer = MutableStateFlow<FlashOffer?>(null)
    val flashOffer: StateFlow<FlashOffer?> = _flashOffer

    // ── Streak ─────────────────────────────────────────────────────────────────
    init {
        SocketClient.init(app)
        SocketClient.connect()

        viewModelScope.launch {
            profile.collect { p ->
                if (p.userId.isNotEmpty() && SocketClient.connectionStatus.value) {
                    rebindSocketSession(p.userId)
                }
            }
        }

        viewModelScope.launch {
            SocketClient.connectionStatus.collect { connected ->
                if (connected) {
                    val p = profile.value
                    if (p.userId.isNotEmpty()) {
                        rebindSocketSession(p.userId)
                    }
                }
            }
        }

        viewModelScope.launch {
            SocketClient.opusFrameFlow.collect { frame ->
                backendTtsFailed = false
                pendingClientTtsText = null
                clientTtsJob?.cancel()
                opusAudioPlayer.startPlayout()
                opusAudioPlayer.handleIncomingOpusFrame(frame.data, frame.seq, frame.ts)
                _jitterStats.value = opusAudioPlayer.getJitterStats()
            }
        }

        viewModelScope.launch {
            SocketClient.iaStateFlow.collect { state ->
                val speaking = (state == "falando")
                if (speaking) {
                    _isIaSpeaking.value = true
                } else {
                    // CRITICAL: do not stopPlayout() here — that cut the last words.
                    // Drain remaining Opus frames already in the jitter buffer.
                    opusAudioPlayer.markStreamEnded()
                    // Keep "speaking" UI briefly while drain finishes
                    viewModelScope.launch {
                        delay(400)
                        // Poll until player drained or timeout (~4s max tail)
                        var waited = 0
                        while (waited < 4000 && opusAudioPlayer.getJitterStats().bufferSize > 0) {
                            delay(100)
                            waited += 100
                        }
                        delay(350)
                        if (SocketClient.iaStateFlow.value != "falando") {
                            _isIaSpeaking.value = false
                            _jitterStats.value = null
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            SocketClient.erroFlow.collect { errorMsg ->
                _toastMessage.value = "Erro no servidor: $errorMsg"
                _isLoading.value = false
                clearResponseTimeout()
                _loadError.value = errorMsg
            }
        }

        viewModelScope.launch {
            SocketClient.ttsUnavailableFlow.collect { reason ->
                // Drop any late primary frames that may still be in the jitter buffer
                opusAudioPlayer.stopPlayout()
                _isIaSpeaking.value = false
                backendTtsFailed = true
                // Backend already falls back to text-only; surface once, never hang spinner
                clearResponseTimeout()
                val friendly = when {
                    reason.contains("api_key_missing", ignoreCase = true) ||
                        reason.contains("ELEVENLABS_API_KEY", ignoreCase = true) ->
                        "Voz: chave ElevenLabs ausente no servidor — tentando fallback local"
                    reason.contains("voice_open_failed", ignoreCase = true) ->
                        "Voz indisponível no servidor — tentando fallback local"
                    reason.contains("first_audio", ignoreCase = true) ||
                        reason.contains("timeout", ignoreCase = true) ->
                        "Voz demorou demais — tentando fallback local"
                    else ->
                        "Voz indisponível (${reason.ifBlank { "erro" }}) — texto mantido"
                }
                if (_isLoading.value && _chatBubbles.value.none { !it.isUser }) {
                    // Still waiting for text — keep loading until texto_chunk or response timeout
                    _toastMessage.value = friendly
                } else {
                    _isLoading.value = false
                    _toastMessage.value = friendly
                    // Prefer pending text; else last Elias bubble
                    if (pendingClientTtsText.isNullOrBlank()) {
                        pendingClientTtsText = _chatBubbles.value
                            .lastOrNull { !it.isUser && it.message.isNotBlank() }
                            ?.message
                    }
                    maybeClientSpeakPending()
                }
            }
        }

        // Progressive TTS status (fallback voice) — light UX; full polish later
        viewModelScope.launch {
            SocketClient.ttsStatusFlow.collect { msg ->
                if (msg.isNotBlank()) {
                    _toastMessage.value = msg
                    // Only hard-stop when actually switching voice (fallback), not for rest_tts
                    val lower = msg.lowercase()
                    if (lower.contains("alternativa") || lower.contains("fallback_voice")) {
                        opusAudioPlayer.stopPlayout()
                    }
                }
            }
        }

        viewModelScope.launch {
            SocketClient.traducaoFlow.collect { result ->
                translationTimeoutJob?.cancel()
                translationTimeoutJob = null
                val idx = result.requestId?.toIntOrNull() ?: pendingTranslationIndex
                if (idx < 0) return@collect
                val bubbles = _chatBubbles.value.toMutableList()
                if (idx >= bubbles.size) return@collect
                val b = bubbles[idx]
                bubbles[idx] = if (result.ok && result.translation.isNotBlank()) {
                    b.copy(
                        translationPt = result.translation,
                        isTranslating = false,
                        translationError = null,
                    )
                } else {
                    b.copy(
                        isTranslating = false,
                        translationError = result.error?.ifBlank { null }
                            ?: "Não foi possível traduzir",
                    )
                }
                _chatBubbles.value = bubbles
                if (!result.ok) {
                    _toastMessage.value = "Tradução: ${result.error ?: "tente de novo"}"
                }
                pendingTranslationIndex = -1
            }
        }

        viewModelScope.launch {
            SocketClient.textoChunkFlow.collect { chunk ->
                _isLoading.value = false
                clearResponseTimeout()
                _loadError.value = null
                val bubbles = _chatBubbles.value.toMutableList()
                if (streamingBubbleIndex != -1 && streamingBubbleIndex < bubbles.size) {
                    val prev = bubbles[streamingBubbleIndex]
                    bubbles[streamingBubbleIndex] = prev.copy(message = prev.message + chunk)
                    pendingClientTtsText = bubbles[streamingBubbleIndex].message
                } else {
                    val newBubble = UiChatBubble(message = chunk, isUser = false)
                    bubbles.add(newBubble)
                    streamingBubbleIndex = bubbles.size - 1
                    pendingClientTtsText = chunk
                }
                _chatBubbles.value = bubbles
            }
        }

        viewModelScope.launch {
            SocketClient.mensagemIaFlow.collect { finalBubble ->
                _isLoading.value = false
                clearResponseTimeout()
                _loadError.value = null
                val bubbles = _chatBubbles.value.toMutableList()
                if (streamingBubbleIndex != -1 && streamingBubbleIndex < bubbles.size) {
                    bubbles[streamingBubbleIndex] = finalBubble
                } else {
                    bubbles.add(finalBubble)
                }
                pendingClientTtsText = finalBubble.message
                if (backendTtsFailed) {
                    maybeClientSpeakPending()
                }
                _chatBubbles.value = bubbles
                streamingBubbleIndex = -1 // Reset for next response

                // Rewards and gamification updates
                val cur = profile.value
                val scenario = _selectedScenario.value
                val scenarioData = GameConstants.SCENARIOS[scenario]
                val xpBonus = scenarioData?.second ?: 0
                val newXp    = cur.xp    + GameConstants.XP_PER_MESSAGE + xpBonus
                val newCoins = cur.coins + GameConstants.COINS_PER_MESSAGE
                val newLevel = computeLevel(newXp)
                var levelCoinsBonus = 0
                if (newLevel > cur.level) {
                    levelCoinsBonus = if (newLevel == 5) 500 else if (newLevel == 10) 1500 else 0
                    if (levelCoinsBonus > 0) _toastMessage.value = "🎉 Level $newLevel! +${levelCoinsBonus} bonus coins!"
                }
                val newErrors = if (finalBubble.mistakes.isNotEmpty()) {
                    val flat = finalBubble.mistakes.joinToString(" | ") {
                        if (it.raw.isNotEmpty()) it.raw else "${it.wrong} → ${it.right}"
                    }
                    (cur.errorLog + ErrorEntry(java.time.Instant.now().toString(), flat)).takeLast(100)
                } else cur.errorLog
                val newXpHist = (cur.xpHistory + XpEntry(java.time.Instant.now().toString(), newXp)).takeLast(200)
                val newSentHist = if (finalBubble.sentiment != "neutral" || finalBubble.sentimentConfidence >= 60) {
                    (cur.sentimentHistory + SentimentEntry(
                        java.time.Instant.now().toString(),
                        finalBubble.sentiment,
                        finalBubble.sentimentConfidence,
                        finalBubble.sentimentCue
                    )).takeLast(50)
                } else cur.sentimentHistory

                var conf = cur.confidence; var post = cur.posture
                when (finalBubble.sentiment) {
                    "enthusiastic" -> { conf = (conf + 2).coerceAtMost(100); post = (post + 2).coerceAtMost(100) }
                    "frustrated"   -> { post = (post - 1).coerceAtLeast(0) }
                }
                ds.save(cur.copy(
                    xp = newXp, coins = newCoins + levelCoinsBonus, level = newLevel,
                    messagesCount = cur.messagesCount + 1,
                    errorLog = newErrors, xpHistory = newXpHist, sentimentHistory = newSentHist,
                    confidence = conf, posture = post,
                ))
            }
        }

        // Initialized SpeechRecognizer on main thread in init
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            initSpeechRecognizer()
        }
        audioCaptureManager.startCapture()
        viewModelScope.launch { 
            val initial = profile.first()
            if (initial.userId.isEmpty()) {
                ds.save(initial.copy(userId = java.util.UUID.randomUUID().toString()))
                profile.first { it.userId.isNotEmpty() }
            }
            syncProfileFromSupabase()
            checkAndUpdateStreak() 
        }
        viewModelScope.launch { loadFlashOffer() }
        
        // Listen to profile changes and sync to Supabase
        viewModelScope.launch {
            profile.drop(1).collect { p ->
                syncProfileToSupabase(p)
            }
        }
    }

    private suspend fun syncProfileFromSupabase() {
        val current = profile.first()
        val sp = SupabaseManager.loadProfile(current.userId) ?: return
        ds.save(current.copy(
            userId = sp.userId,
            xp = sp.xp,
            coins = sp.coins,
            level = sp.level,
            britishUnlocked = sp.britishUnlocked,
            messagesCount = sp.messagesSent,
            errorLog = sp.errorLog,
            confidence = sp.softSkills.confidence,
            clarity = sp.softSkills.clarity,
            posture = sp.softSkills.posture,
            softSkillsSummary = sp.softSkills.summary,
            sentimentHistory = sp.sentimentHistory,
            xpHistory = sp.xpHistory
        ))
    }

    private suspend fun syncProfileToSupabase(p: UserProfile) {
        val sp = SupabaseProfile(
            userId = p.userId,
            xp = p.xp,
            coins = p.coins,
            level = p.level,
            britishUnlocked = p.britishUnlocked,
            messagesSent = p.messagesCount,
            errorLog = p.errorLog,
            softSkills = SoftSkills(p.confidence, p.clarity, p.posture, p.softSkillsSummary),
            sentimentHistory = p.sentimentHistory,
            xpHistory = p.xpHistory
        )
        SupabaseManager.upsertProfile(sp)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STREAK
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun checkAndUpdateStreak() {
        val p = profile.first()
        val today     = LocalDate.now().toString()
        val yesterday = LocalDate.now().minusDays(1).toString()
        if (p.lastActiveDate == today) return

        val newStreak: Int
        var coinsDelta = 0
        var newFreeze  = p.streakFreezeCount

        when (p.lastActiveDate) {
            "" -> { newStreak = 1 }
            yesterday -> {
                newStreak  = p.streak + 1
                coinsDelta = GameConstants.STREAK_BONUS_COINS
                _toastMessage.value = "🔥 ${newStreak}-day streak! +${GameConstants.STREAK_BONUS_COINS} coins!"
            }
            else -> {
                if (p.streakFreezeCount > 0) {
                    newStreak = p.streak
                    newFreeze = p.streakFreezeCount - 1
                    _toastMessage.value = "🛡️ Streak Freeze used! Streak preserved."
                } else {
                    newStreak = 1
                    _toastMessage.value = "💔 Streak reset. Keep going!"
                }
            }
        }
        ds.save(p.copy(streak = newStreak, lastActiveDate = today,
            coins = p.coins + coinsDelta, streakFreezeCount = newFreeze))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHAT — Claude
    // ─────────────────────────────────────────────────────────────────────────
    fun selectScenario(name: String) { _selectedScenario.value = name }

    /** Keep program prompt on reconnect; otherwise default free chat session. */
    private fun rebindSocketSession(userId: String) {
        val ctx = _chatContext.value
        if (ctx.type == com.roberto.eliasaitutor.model.ChatType.PROGRAM && ctx.week != null) {
            SocketClient.iniciarSessaoPrograma(userId, ctx.week!!, ctx.sessionType)
        } else {
            SocketClient.iniciarSessao(userId)
        }
    }

    /**
     * PROGRAM flow (Task Final / A.1): no level picker, week prompt, auto TTS via backend stream.
     * [level] must come from program_weeks.level for the current week — never user self-report.
     */
    fun beginProgramSession(
        week: Int,
        title: String,
        lexis: String,
        grammar: String,
        phase: Int,
        sessionType: String,
        userId: String,
        level: String = "",
    ) {
        _chatContext.value = com.roberto.eliasaitutor.model.ChatContext(
            type = com.roberto.eliasaitutor.model.ChatType.PROGRAM,
            week = week,
            title = title,
            lexis = lexis,
            grammar = grammar,
            phase = phase,
            sessionType = sessionType,
            level = level,
        )
        _chatBubbles.value = emptyList()
        claudeHistory.clear()
        streamingBubbleIndex = -1
        isInterrupted = false
        _selectedScenario.value = ""
        _isLoading.value = true
        armResponseTimeout()

        SocketClient.iniciarSessaoPrograma(userId, week, sessionType)

        // Brief delay so backend locks program prompt + TTS before kickoff (lower first-byte lag)
        viewModelScope.launch {
            kotlinx.coroutines.delay(450)
            val dayFocus = PronunciationFocus.focusOfDay()
            val cefr = level.ifBlank { "from week curriculum" }
            val kickoff =
                "[PROGRAM_SESSION_START] Roberto is ready for today's ${sessionType} session. " +
                    "Week $week — $title (CEFR $cefr). Theme/lexis: $lexis. Grammar: $grammar. " +
                    "MODE: Pronúncia Avançada Máxima ON. Do NOT ask level — curriculum level is $cefr. " +
                    "${PronunciationFocus.kickoffHint(dayFocus)} " +
                    "Open with the official Portuguese greeting, then coach with full focus on " +
                    "IPA + Shadowing + Schwa + Linked Speech + Elision + Intonation " +
                    "(prioritize $dayFocus today). " +
                    "Use ready drills (1–5) with phrase+IPA+contrast+shadowing; demand excellence before next phrase. " +
                    "TTS streams automatically — speak naturally for voice."
            SocketClient.enviarMensagem(kickoff)
        }
    }

    fun endProgramSession() {
        _chatContext.value = com.roberto.eliasaitutor.model.ChatContext(
            type = com.roberto.eliasaitutor.model.ChatType.FREE
        )
        val p = profile.value
        if (p.userId.isNotEmpty() && SocketClient.connectionStatus.value) {
            SocketClient.iniciarSessao(p.userId)
        }
    }

    private var translationTimeoutJob: Job? = null
    private val translationTimeoutMs = 12_000L

    /** Translate Elias bubble at [index] via backend LLM — PT under EN, never replaces original. */
    fun translateBubble(index: Int) {
        val bubbles = _chatBubbles.value
        if (index !in bubbles.indices) return
        val b = bubbles[index]
        if (b.isUser || b.message.isBlank()) return
        if (b.translationPt != null) return // already translated
        if (!SocketClient.connectionStatus.value) {
            _toastMessage.value = "Offline — conecte-se para traduzir"
            _chatBubbles.value = bubbles.toMutableList().also {
                it[index] = b.copy(
                    isTranslating = false,
                    translationError = "Offline — toque para tentar de novo",
                )
            }
            return
        }
        pendingTranslationIndex = index
        translationTimeoutJob?.cancel()
        _chatBubbles.value = bubbles.toMutableList().also {
            it[index] = b.copy(isTranslating = true, translationError = null)
        }
        SocketClient.requestTranslation(b.message, requestId = index.toString())
        // A.5: never leave "Traduzindo…" forever
        translationTimeoutJob = viewModelScope.launch {
            delay(translationTimeoutMs)
            val cur = _chatBubbles.value.toMutableList()
            if (index !in cur.indices) return@launch
            val bb = cur[index]
            if (bb.isTranslating && bb.translationPt == null) {
                cur[index] = bb.copy(
                    isTranslating = false,
                    translationError = "Tempo esgotado — toque para tentar de novo",
                )
                _chatBubbles.value = cur
                _toastMessage.value = "Tradução demorou demais"
                if (pendingTranslationIndex == index) pendingTranslationIndex = -1
            }
        }
    }

    /** Translate the last Elias message (voice: "não entendi", "traduz pra mim"). */
    fun translateLastEliasMessage() {
        val idx = _chatBubbles.value.indexOfLast { !it.isUser && it.message.isNotBlank() }
        if (idx >= 0) {
            translateBubble(idx)
        } else {
            _toastMessage.value = "Nenhuma mensagem do Elias para traduzir ainda"
        }
    }

    /**
     * True when the user utterance is only a translation request (no extra content).
     * In that case we translate in-place and skip a new chat turn.
     */
    fun isPureTranslationRequest(userText: String): Boolean {
        val t = userText.lowercase().trim()
            .replace(Regex("[.!?…]+$"), "")
            .trim()
        if (t.isBlank()) return false
        val pure = setOf(
            "não entendi", "nao entendi",
            "traduz", "traduza", "traduzir", "traduza pra mim", "traduz pra mim",
            "traduza para mim", "traduz para mim", "traduza isso", "traduz isso",
            "me traduz", "me traduza", "em português", "em portugues",
            "o que significa", "o que que significa", "what does it mean",
            "translate", "translate please", "i don't understand", "i dont understand",
        )
        if (t in pure) return true
        // Short help phrases that clearly ask only for translation
        return (t.startsWith("traduz") || t.startsWith("traduza") || t.startsWith("não entendi") ||
            t.startsWith("nao entendi") || t.startsWith("me traduz") || t.startsWith("em portugu")) &&
            t.length <= 40
    }

    fun sendMessage(userText: String) {
        val isProgram =
            _chatContext.value.type == com.roberto.eliasaitutor.model.ChatType.PROGRAM
        val scenario = _selectedScenario.value
        val scenarioData = GameConstants.SCENARIOS[scenario]
        val minLevel = scenarioData?.first ?: 1
        val p = profile.value

        // Voice/command: translate last Elias turn
        val lower = userText.lowercase()
        val wantsTranslation =
            lower.contains("não entendi") || lower.contains("nao entendi") ||
                lower.contains("traduz") || lower.contains("traduza") ||
                lower.contains("translate") || lower.contains("em português") ||
                lower.contains("em portugues") || lower.contains("o que significa") ||
                lower.contains("me traduz")

        if (wantsTranslation) {
            translateLastEliasMessage()
            // Pure translate request → only show PT under last message (no new chat turn)
            if (isPureTranslationRequest(userText)) {
                return
            }
            // Longer utterance still goes to Elias so he can rephrase simply
        }

        // Scenario gates only apply outside program mode
        if (!isProgram && p.level < minLevel && scenario !in p.unlockedScenarios) {
            _toastMessage.value = "🔒 Requires Level $minLevel"
            return
        }

        val isFirstMessage = _chatBubbles.value.isEmpty()
        val enriched = when {
            isProgram -> {
                // Never inject free-chat level profile in PROGRAM mode
                userText
            }
            isFirstMessage -> {
                "Student English Level Profile: $userText\nPlease introduce yourself as Elias and start the conversation immediately matching this level."
            }
            scenario.isNotEmpty() -> "[Scenario: $scenario]\n$userText"
            else -> userText
        }

        // FREE first message is the level chip (not shown as bubble).
        // PROGRAM always shows Roberto's turns.
        if (isProgram || !isFirstMessage) {
            _chatBubbles.value = _chatBubbles.value + UiChatBubble(userText, isUser = true)
        }

        SocketClient.enviarMensagem(enriched)

        _isLoading.value = true
        isInterrupted = false
        streamingBubbleIndex = -1
        backendTtsFailed = false
        pendingClientTtsText = null
        armResponseTimeout()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SHADOWING
    // ─────────────────────────────────────────────────────────────────────────
    private val _shadowIpa = MutableStateFlow("")
    val shadowIpa: StateFlow<String> = _shadowIpa

    fun generateShadowPhrase() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = AnthropicClient.api.generateMessage(ClaudeRequest(
                    messages = listOf(ClaudeMessage("user",
                        "Generate ONE natural General American English sentence (10-18 words) for pronunciation shadowing. " +
                        "Include schwa/linking where natural. Reply ONLY with JSON: " +
                        "{\"phrase\":\"...\",\"ipa\":\"/.../\"}"))
                ))
                val raw = resp.content.firstOrNull()?.text?.trim().orEmpty()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                try {
                    val obj = JSONObject(raw)
                    _shadowPhrase.value = obj.optString("phrase").ifBlank {
                        "I want to go to America next summer."
                    }
                    _shadowIpa.value = obj.optString("ipa")
                } catch (_: Exception) {
                    _shadowPhrase.value = raw.trim('"').ifBlank {
                        "I want to go to America next summer."
                    }
                    _shadowIpa.value = "/aɪ ˈwɑnə ɡoʊ tə əˈmɛɹɪkə nɛkst ˈsʌmɚ/"
                }
                _shadowScore.value = null
                _shadowFeedback.value = ""
            } catch (e: Exception) {
                _shadowPhrase.value = "I want to go to America next summer."
                _shadowIpa.value = "/aɪ ˈwɑnə ɡoʊ tə əˈmɛɹɪkə nɛkst ˈsʌmɚ/"
            } finally { _isLoading.value = false }
        }
    }

    /** Score from known transcript (no audio file) — LLM or word-overlap. */
    fun scoreShadowing(reference: String, transcribed: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _shadowTranscript.value = transcribed
            try {
                val focus = PronunciationFocus.focusOfDay()
                val localOverlap = estimateWordOverlap(reference, transcribed)
                try {
                    val resp = AnthropicClient.api.generateMessage(
                        ClaudeRequest(
                            max_tokens = 80,
                            messages = listOf(
                                ClaudeMessage(
                                    "user",
                                    "Reference: \"$reference\"\nStudent said: \"$transcribed\"\n" +
                                        "Focus: $focus\n" +
                                        "Score pronunciation 0-100. Reply ONLY with JSON: " +
                                        "{\"score\":<int>,\"feedback\":\"<sentence in PT-BR>\"}"
                                )
                            )
                        )
                    )
                    val raw = resp.content.firstOrNull()?.text?.trim()
                        ?: "{\"score\":$localOverlap,\"feedback\":\"Continue praticando!\"}"
                    val obj = JSONObject(
                        raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    )
                    val sc = obj.optInt("score", localOverlap).coerceIn(0, 100)
                    val blended = ((sc * 0.75) + (localOverlap * 0.25)).toInt().coerceIn(0, 100)
                    applyShadowScore(
                        blended,
                        obj.optString("feedback", "Continue praticando!"),
                        awardRewards = true,
                    )
                } catch (_: Exception) {
                    applyShadowScore(
                        localOverlap,
                        "Você disse: “$transcribed”. ${PronunciationFocus.coachingTip(focus)}",
                        awardRewards = true,
                    )
                }
            } catch (e: Exception) {
                applyShadowScore(60, "Continue praticando!", awardRewards = false)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun estimateWordOverlap(reference: String, hypothesis: String): Int {
        fun words(s: String) = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        val ref = words(reference)
        val hyp = words(hypothesis)
        if (ref.isEmpty()) return 50
        if (hyp.isEmpty()) return 15
        val set = hyp.toSet()
        val hit = ref.count { it in set }
        val coverage = hit.toDouble() / ref.size
        val extra = (hyp.size - ref.size).coerceAtLeast(0).toDouble() / ref.size
        return (coverage * 100 - extra * 12).toInt().coerceIn(10, 98)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QUIZ — DeepSeek
    // ─────────────────────────────────────────────────────────────────────────
    fun generateQuiz() {
        viewModelScope.launch {
            _isLoading.value  = true
            _quizAnswered.value = false
            try {
                val recentVocab = _chatBubbles.value
                    .filter { !it.isUser }.flatMap { it.vocabulary }
                    .map { it.substringBefore(":").trim() }
                    .takeLast(6)
                    .joinToString(", ").ifEmpty { "common English phrases" }

                val resp = DeepSeekClient.api.chat(
                    com.roberto.eliasaitutor.network.DSRequest(
                        temperature = 0.8,
                        messages = listOf(DSMessage("user",
                            "Generate ONE multiple-choice vocab quiz for an English learner.\n" +
                            "Base it on: $recentVocab\n" +
                            "4 options (A-D), one correct, intermediate difficulty.\n" +
                            "Respond ONLY with valid JSON:\n" +
                            "{\"question\":\"<str>\",\"options\":[\"<A>\",\"<B>\",\"<C>\",\"<D>\"]," +
                            "\"correct_index\":<0-3>,\"explanation\":\"<1 sentence>\"}"
                        ))
                    )
                )
                val raw = resp.choices.firstOrNull()?.message?.content
                    ?.removePrefix("```json")?.removeSuffix("```")?.trim() ?: ""
                val obj = JSONObject(raw)
                val opts = (0 until obj.getJSONArray("options").length())
                    .map { obj.getJSONArray("options").getString(it) }
                _quiz.value = QuizQuestion(
                    question     = obj.getString("question"),
                    options      = opts,
                    correctIndex = obj.getInt("correct_index"),
                    explanation  = obj.getString("explanation"),
                )
            } catch (e: Exception) {
                _quiz.value = QuizQuestion(
                    "Which sentence is grammatically correct?",
                    listOf("I have went there.", "I have gone there.", "I has gone there.", "I went there already."),
                    1, "\"I have gone\" uses the present perfect correctly with an irregular past participle."
                )
            } finally { _isLoading.value = false }
        }
    }

    fun submitQuizAnswer(chosen: Int): Boolean {
        val correct = _quiz.value?.correctIndex ?: return false
        _quizAnswered.value = true
        if (chosen == correct) {
            viewModelScope.launch {
                val cur = profile.first()
                ds.save(cur.copy(xp = cur.xp + GameConstants.QUIZ_XP, coins = cur.coins + GameConstants.QUIZ_COINS))
            }
            return true
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FLASH OFFER — DeepSeek
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun loadFlashOffer() {
        val today = LocalDate.now().toString()
        val supabaseOffer = SupabaseManager.loadFlashOffer(today)
        if (supabaseOffer != null) {
            val offer = FlashOffer(
                title = supabaseOffer.title,
                description = supabaseOffer.description,
                discountPct = supabaseOffer.discountPct,
                target = supabaseOffer.target,
                priceOriginal = supabaseOffer.priceOriginal,
                priceFinal = supabaseOffer.priceFinal,
                offerDate = supabaseOffer.offerDate
            )
            _flashOffer.value = offer
            ds.saveFlashOffer(JSONObject(mapOf(
                "title" to offer.title,
                "description" to offer.description,
                "discount_pct" to offer.discountPct,
                "target" to offer.target,
                "price_original" to offer.priceOriginal,
                "price_final" to offer.priceFinal
            )).toString(), today)
            return
        }

        val flashData = ds.loadFlashOffer()
        val cached = flashData.first
        val cachedDate = flashData.second
        if (cachedDate == today && cached.isNotEmpty()) {
            runCatching {
                val obj = JSONObject(cached)
                _flashOffer.value = FlashOffer(
                    title         = obj.optString("title"),
                    description   = obj.optString("description"),
                    discountPct   = obj.optInt("discount_pct", 50),
                    target        = obj.optString("target", "british_accent"),
                    priceOriginal = obj.optInt("price_original", GameConstants.BRITISH_COST),
                    priceFinal    = obj.optInt("price_final", GameConstants.BRITISH_COST / 2),
                    offerDate     = today,
                )
            }
            return
        }
        // Generate new offer via DeepSeek
        try {
            val resp = DeepSeekClient.api.chat(
                DSRequest(temperature = 0.9, messages = listOf(DSMessage("user",
                    "You are a growth hacker for Elias, a gamified English tutoring app.\n" +
                    "Today: $today\n\n" +
                    "Generate ONE creative flash offer for today. Requirements:\n" +
                    "- Real discount between 20%-70%\n" +
                    "- Target: British Accent, Level 5 Early Access, Level 10 Early Access, or XP Booster Pack\n" +
                    "- Feel urgent and time-limited; use an emoji in the title\n" +
                    "- Friendly tone for English language learners\n\n" +
                    "Respond ONLY with valid JSON (no markdown backticks, no preamble):\n" +
                    "{\"title\":\"<str>\",\"description\":\"<str>\",\"discount_pct\":<int>," +
                    "\"target\":\"british_accent|level5_access|level10_access|xp_booster\"," +
                    "\"price_original\":<int>,\"price_final\":<int>,\"offer_date\":\"$today\"}"
                )))
            )
            val raw = resp.choices.firstOrNull()?.message?.content
                ?.removePrefix("```json")?.removeSuffix("```")?.trim() ?: ""
            val obj = JSONObject(raw)
            val offer = FlashOffer(
                title         = obj.optString("title"),
                description   = obj.optString("description"),
                discountPct   = obj.optInt("discount_pct", 50),
                target        = obj.optString("target", "british_accent"),
                priceOriginal = obj.optInt("price_original", GameConstants.BRITISH_COST),
                priceFinal    = obj.optInt("price_final", GameConstants.BRITISH_COST / 2),
                offerDate     = today,
            )
            _flashOffer.value = offer
            ds.saveFlashOffer(raw, today)
            SupabaseManager.saveFlashOffer(SupabaseFlashOffer(
                offerDate = today,
                title = offer.title,
                description = offer.description,
                discountPct = offer.discountPct,
                target = offer.target,
                priceOriginal = offer.priceOriginal,
                priceFinal = offer.priceFinal
            ))
        } catch (e: Exception) {
            _flashOffer.value = FlashOffer(
                title = "⚡ 50% OFF British Accent — Today Only!",
                description = "Sound like a proper Brit. Unlock the British RP accent at half price.",
                discountPct = 50, target = "british_accent",
                priceOriginal = GameConstants.BRITISH_COST,
                priceFinal    = GameConstants.BRITISH_COST / 2,
                offerDate = today, isFallback = true,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STORE ACTIONS
    // ─────────────────────────────────────────────────────────────────────────
    fun buyBritishAccent(price: Int = GameConstants.BRITISH_COST): Boolean {
        val p = profile.value
        if (p.coins < price) return false
        viewModelScope.launch { ds.save(p.copy(coins = p.coins - price, britishUnlocked = true)) }
        return true
    }

    fun buyStreakFreeze(): Boolean {
        val p = profile.value
        if (p.coins < GameConstants.STREAK_FREEZE_COST) return false
        viewModelScope.launch { ds.save(p.copy(coins = p.coins - GameConstants.STREAK_FREEZE_COST, streakFreezeCount = p.streakFreezeCount + 1)) }
        return true
    }

    fun buyScenarioAccess(scenario: String, price: Int = GameConstants.EARLY_ACCESS_COST): Boolean {
        val p = profile.value
        if (p.coins < price) return false
        val newUnlocked = (p.unlockedScenarios + scenario).distinct()
        viewModelScope.launch { ds.save(p.copy(coins = p.coins - price, unlockedScenarios = newUnlocked)) }
        return true
    }

    fun claimFlashOffer() {
        val offer = _flashOffer.value ?: return
        val p = profile.value
        val price = offer.priceFinal
        if (p.coins < price) { _toastMessage.value = "Need ${price - p.coins} more coins."; return }
        viewModelScope.launch {
            val updated = when {
                "british" in offer.target -> p.copy(coins = p.coins - price, britishUnlocked = true)
                else -> p.copy(coins = p.coins - price)
            }
            ds.save(updated)
            _toastMessage.value = "✅ ${offer.title} claimed!"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SOFT SKILLS — DeepSeek analysis
    // ─────────────────────────────────────────────────────────────────────────
    fun analyzeSoftSkills() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val userMsgs = _chatBubbles.value.filter { it.isUser }.map { it.message }.takeLast(20)
                    .joinToString("\n") { "Student: $it" }
                val cur = profile.first()
                val resp = DeepSeekClient.api.chat(DSRequest(
                    temperature = 0.3,
                    messages = listOf(DSMessage("user",
                        "You are an expert communication coach analyzing an English learner.\n\n" +
                        "Recent student messages:\n$userMsgs\n\n" +
                        "Total grammar mistakes logged: ${cur.errorLog.size}\n\n" +
                        "Score these 3 soft skills 0-100 based ONLY on the messages:\n" +
                        "1. CONFIDENCE: assertive language, complete sentences, taking initiative\n" +
                        "2. CLARITY: clear structure, easy to understand, logical flow\n" +
                        "3. POSTURE: positive attitude, resilience, engagement level\n\n" +
                        "Respond ONLY with valid JSON:\n" +
                        "{\"confidence\":<int>,\"clarity\":<int>,\"posture\":<int>,\"summary\":\"<2 sentences>\"}"
                    ))
                ))
                val raw = resp.choices.firstOrNull()?.message?.content
                    ?.removePrefix("```json")?.removeSuffix("```")?.trim() ?: ""
                val obj = JSONObject(raw)
                ds.save(cur.copy(
                    confidence       = obj.optInt("confidence", 50).coerceIn(0, 100),
                    clarity          = obj.optInt("clarity",    50).coerceIn(0, 100),
                    posture          = obj.optInt("posture",    50).coerceIn(0, 100),
                    softSkillsSummary = obj.optString("summary", ""),
                ))
            } catch (e: Exception) {
                /* keep existing values */
            } finally { _isLoading.value = false }
        }
    }

    suspend fun generatePdfNarrative(p: UserProfile): String {
        return try {
            val prompt = "Write a personalized 3-paragraph coaching narrative for an English student report.\n" +
                "Stats: Level=${p.level}, XP=${p.xp}, Messages=${p.messagesCount}, Mistakes=${p.errorLog.size}\n" +
                "Soft Skills: Confidence=${p.confidence}, Clarity=${p.clarity}, Posture=${p.posture}\n\n" +
                "Paragraph 1: Genuine overall progress praise.\n" +
                "Paragraph 2: Specific strengths from the soft skill scores.\n" +
                "Paragraph 3: 2-3 concrete, actionable next steps.\n" +
                "Return only the three paragraphs, no headers, no bullet points."
            
            val resp = DeepSeekClient.api.chat(DSRequest(
                temperature = 0.7,
                messages = listOf(DSMessage("user", prompt))
            ))
            resp.choices.firstOrNull()?.message?.content?.trim() ?: fallbackNarrative()
        } catch (e: Exception) {
            fallbackNarrative()
        }
    }

    private fun fallbackNarrative() = 
        "You're making excellent progress on your English journey! Every message you send is building your fluency and confidence.\n\n" +
        "Your consistency is your biggest strength. Keep engaging with Elias daily to see rapid improvement.\n\n" +
        "Next steps: Try the Job Interview scenario, practice shadowing daily, and aim to use each new vocabulary word 3 times this week."

    fun clearToast() { _toastMessage.value = null }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private fun computeLevel(xp: Int): Int = when {
        xp >= GameConstants.LEVEL_THRESHOLDS[10]!! -> 10
        xp >= GameConstants.LEVEL_THRESHOLDS[5]!!  -> 5
        else -> 1
    }

    private fun parseClaudeResponse(raw: String): ParsedResponse {
        fun tag(name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)?.trim()

        val response = tag("RESPONSE") ?: raw
        val vocabRaw = tag("VOCABULARY") ?: ""
        val vocab    = vocabRaw.lines().filter { it.isNotBlank() }
        val mistakeRaw = tag("MISTAKE_LOG") ?: ""
        val mistakes = mistakeRaw.lines()
            .filter { it.isNotBlank() && it.lowercase() != "none" }
            .map { line ->
                val body = line.replace(Regex("^Mistake\\s*\\d+:\\s*", RegexOption.IGNORE_CASE), "")
                if ("→" in body) {
                    val parts = body.split("→", limit = 2)
                    val left = parts.getOrNull(0)?.trim() ?: ""
                    val rightPart = parts.getOrNull(1)?.trim() ?: ""
                    
                    val rightRuleParts = if ("| Rule:" in rightPart)
                        rightPart.split("| Rule:", limit = 2)
                    else listOf(rightPart, "")
                    
                    val right = rightRuleParts.getOrNull(0)?.trim() ?: ""
                    val rule = rightRuleParts.getOrNull(1)?.trim() ?: ""
                    
                    MistakeEntry(left, right, rule)
                } else MistakeEntry(raw = line)
            }
        val sentBlock = tag("SENTIMENT") ?: ""
        val detected  = Regex("detected:\\s*(\\w+)").find(sentBlock)?.groupValues?.get(1)?.lowercase() ?: "neutral"
        val confidence= Regex("confidence:\\s*(\\d+)").find(sentBlock)?.groupValues?.get(1)?.toIntOrNull() ?: 50
        val cue       = Regex("cue:\\s*(.+)").find(sentBlock)?.groupValues?.get(1)?.trim() ?: ""

        return ParsedResponse(response, vocab, mistakes, detected, confidence.coerceIn(0,100), cue)
    }

    override fun onCleared() {
        clientTtsJob?.cancel()
        try {
            localMediaPlayer?.release()
        } catch (_: Exception) {
        }
        localMediaPlayer = null
        try {
            audioHelper.stopPlaying()
        } catch (_: Exception) {
        }
        try {
            opusAudioPlayer.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            fallbackPcmPlayer.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            audioCaptureManager.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onCleared()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            SocketClient.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            EliasViewModel(app) as T
    }
}

class PcmFloatPlayer {
    private var audioTrack: android.media.AudioTrack? = null
    
    init {
        try {
            val sampleRate = 44100
            val channelConfig = android.media.AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_FLOAT
            val bufferSize = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            audioTrack = android.media.AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
                android.media.AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun playPcmData(data: ByteArray) {
        val track = audioTrack ?: return
        try {
            val floatArray = java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
            val floats = FloatArray(floatArray.remaining())
            floatArray.get(floats)
            track.write(floats, 0, floats.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun flush() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
    }
}
