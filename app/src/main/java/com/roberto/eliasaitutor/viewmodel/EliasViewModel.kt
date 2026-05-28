package com.roberto.eliasaitutor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roberto.eliasaitutor.data.DataStoreManager
import com.roberto.eliasaitutor.data.GameConstants
import com.roberto.eliasaitutor.model.*
import com.roberto.eliasaitutor.network.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage

    private val audioEngine = com.roberto.eliasaitutor.audio.AudioEngine(app)
    private var isInterrupted = false
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isIaSpeaking = MutableStateFlow(false)
    val isIaSpeaking: StateFlow<Boolean> = _isIaSpeaking

    private var streamingBubbleIndex = -1

    fun startListening() {
        audioEngine.startListening()
        _isRecording.value = true
    }

    fun stopListening() {
        audioEngine.stopListening()
        _isRecording.value = false
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
            audioEngine.flushAudio()
            _isLoading.value = false
        }
    }


    fun speakText(text: String, onCompletion: () -> Unit = {}) {
        // Fallback for immersion / shadowing
        com.roberto.eliasaitutor.network.CartesiaClient.sendChunk(text, true, java.util.UUID.randomUUID().toString())
        onCompletion()
    }

    fun submitShadowingAudio(audioFile: java.io.File, phrase: String = "") {
        // Dummy implementation
    }

    fun playLocalFile(file: java.io.File, onCompletion: () -> Unit = {}) {
        // Dummy implementation
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
        CartesiaClient.connect()
        SocketClient.connect()

        viewModelScope.launch {
            profile.collect { p ->
                if (p.userId.isNotEmpty() && SocketClient.connectionStatus.value) {
                    SocketClient.iniciarSessao(p.userId)
                }
            }
        }

        viewModelScope.launch {
            SocketClient.connectionStatus.collect { connected ->
                if (connected) {
                    val p = profile.value
                    if (p.userId.isNotEmpty()) {
                        SocketClient.iniciarSessao(p.userId)
                    }
                }
            }
        }

        viewModelScope.launch {
            CartesiaClient.audioFlow.collect { pcmData ->
                audioEngine.playPcmData(pcmData)
            }
        }

        viewModelScope.launch {
            SocketClient.audioFlow.collect { pcmData ->
                audioEngine.playPcmData(pcmData)
            }
        }

        viewModelScope.launch {
            SocketClient.iaStateFlow.collect { state ->
                _isIaSpeaking.value = (state == "falando")
            }
        }

        viewModelScope.launch {
            SocketClient.erroFlow.collect { errorMsg ->
                _toastMessage.value = "Erro no servidor: $errorMsg"
                _isLoading.value = false
            }
        }

        viewModelScope.launch {
            SocketClient.textoChunkFlow.collect { chunk ->
                _isLoading.value = false
                val bubbles = _chatBubbles.value.toMutableList()
                if (streamingBubbleIndex != -1 && streamingBubbleIndex < bubbles.size) {
                    val prev = bubbles[streamingBubbleIndex]
                    bubbles[streamingBubbleIndex] = prev.copy(message = prev.message + chunk)
                } else {
                    val newBubble = UiChatBubble(message = chunk, isUser = false)
                    bubbles.add(newBubble)
                    streamingBubbleIndex = bubbles.size - 1
                }
                _chatBubbles.value = bubbles
            }
        }

        viewModelScope.launch {
            SocketClient.mensagemIaFlow.collect { finalBubble ->
                _isLoading.value = false
                val bubbles = _chatBubbles.value.toMutableList()
                if (streamingBubbleIndex != -1 && streamingBubbleIndex < bubbles.size) {
                    bubbles[streamingBubbleIndex] = finalBubble
                } else {
                    bubbles.add(finalBubble)
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

        viewModelScope.launch {
            audioEngine.userSpeechStarted.collect {
                interruptAi()
            }
        }
        viewModelScope.launch {
            audioEngine.userSpeechResult.collect { text ->
                sendMessage(text)
            }
        }
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

    fun sendMessage(userText: String) {
        val scenario = _selectedScenario.value
        val scenarioData = GameConstants.SCENARIOS[scenario]
        val minLevel = scenarioData?.first ?: 1
        val p = profile.value

        if (p.level < minLevel && scenario !in p.unlockedScenarios) {
            _toastMessage.value = "🔒 Requires Level $minLevel"
            return
        }

        val isFirstMessage = _chatBubbles.value.isEmpty()
        val enriched = if (isFirstMessage) {
            "Student English Level Profile: $userText\nPlease introduce yourself as Elias and start the conversation immediately matching this level."
        } else if (scenario.isNotEmpty()) {
            "[Scenario: $scenario]\n$userText"
        } else {
            userText
        }

        if (!isFirstMessage) {
            _chatBubbles.value = _chatBubbles.value + UiChatBubble(userText, isUser = true)
        }
        
        SocketClient.enviarMensagem(enriched)
        
        _isLoading.value = true
        isInterrupted = false
        streamingBubbleIndex = -1
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SHADOWING
    // ─────────────────────────────────────────────────────────────────────────
    fun generateShadowPhrase() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = AnthropicClient.api.generateMessage(ClaudeRequest(
                    messages = listOf(ClaudeMessage("user",
                        "Generate ONE natural American English sentence (10-18 words) for pronunciation shadowing. " +
                        "Conversational, intermediate level. Return ONLY the sentence."))
                ))
                _shadowPhrase.value   = resp.content.firstOrNull()?.text?.trim()?.trim('"') ?: ""
                _shadowScore.value    = null
                _shadowFeedback.value = ""
            } catch (e: Exception) {
                _shadowPhrase.value = "The weather in California is usually sunny and warm throughout the year."
            } finally { _isLoading.value = false }
        }
    }

    fun scoreShadowing(reference: String, transcribed: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = AnthropicClient.api.generateMessage(ClaudeRequest(
                    max_tokens = 80,
                    messages = listOf(ClaudeMessage("user",
                        "Reference: \"$reference\"\nStudent said: \"$transcribed\"\n\n" +
                        "Score pronunciation 0-100. Reply ONLY with JSON: {\"score\":<int>,\"feedback\":\"<sentence>\"}"))
                ))
                val raw = resp.content.firstOrNull()?.text?.trim() ?: "{\"score\":60,\"feedback\":\"Keep practicing!\"}"
                val obj = JSONObject(raw.removePrefix("```json").removeSuffix("```").trim())
                _shadowScore.value    = obj.optInt("score", 60).coerceIn(0, 100)
                _shadowFeedback.value = obj.optString("feedback", "Keep practicing!")

                // reward + clarity update
                val cur = profile.first()
                val sc  = _shadowScore.value ?: 60
                ds.save(cur.copy(
                    xp    = cur.xp    + GameConstants.SHADOWING_XP,
                    coins = cur.coins + GameConstants.SHADOWING_COINS,
                    clarity = (cur.clarity * 0.7 + sc * 0.3).toInt().coerceIn(0, 100),
                ))
            } catch (e: Exception) {
                _shadowScore.value    = 60
                _shadowFeedback.value = "Keep practicing!"
            } finally { _isLoading.value = false }
        }
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
        super.onCleared()
        try {
            audioEngine.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            SocketClient.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            CartesiaClient.disconnect()
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
