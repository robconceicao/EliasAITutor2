package com.roberto.eliasaitutor.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.roberto.eliasaitutor.BuildConfig
import com.roberto.eliasaitutor.model.MistakeEntry
import com.roberto.eliasaitutor.model.UiChatBubble
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.math.min
import kotlin.math.pow

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
}

data class ChatSession(
    val sessionId: String,
    val messageHistory: MutableList<JSONObject> = mutableListOf()
)

data class OpusFrame(
    val data: ByteArray,
    val seq: Int,
    val ts: Long
)

object SocketClient {
    private const val TAG = "SocketClient"
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 30_000L
    private const val MAX_ATTEMPTS = 10
    private const val HEARTBEAT_INTERVAL_MS = 25_000L

    private var socket: Socket? = null
    private var currentSession: ChatSession? = null
    private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var isIntentionalDisconnect = false
    private var appContext: Context? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Mantido para compatibilidade com código existente (ViewModel)
    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus

    private val _audioFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFlow: SharedFlow<ByteArray> = _audioFlow

    private val _opusFrameFlow = MutableSharedFlow<OpusFrame>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val opusFrameFlow: SharedFlow<OpusFrame> = _opusFrameFlow

    private val _iaStateFlow = MutableStateFlow("ociosa") // "ociosa" ou "falando"
    val iaStateFlow: StateFlow<String> = _iaStateFlow

    private val _erroFlow = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val erroFlow: SharedFlow<String> = _erroFlow

    private val _textoChunkFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val textoChunkFlow: SharedFlow<String> = _textoChunkFlow

    private val _mensagemIaFlow = MutableSharedFlow<UiChatBubble>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val mensagemIaFlow: SharedFlow<UiChatBubble> = _mensagemIaFlow

    // Monitora as mudanças de rede
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Rede disponível — verificando conexão socket")
            if (_connectionState.value == ConnectionState.DISCONNECTED ||
                _connectionState.value == ConnectionState.RECONNECTING) {
                reconnectAttempts = 0
                scheduleReconnect(500)
            }
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "Rede perdida — aguardando reconexão")
            _connectionState.value = ConnectionState.RECONNECTING
            _connectionStatus.value = false
            stopHeartbeat()
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        registerNetworkCallback()
    }

    fun connect() {
        isIntentionalDisconnect = false
        if (currentSession == null) {
            currentSession = ChatSession(generateSessionId())
        }
        establishConnection()
    }

    private fun establishConnection() {
        socket?.disconnect()

        _connectionState.value = if (reconnectAttempts == 0)
            ConnectionState.CONNECTING else ConnectionState.RECONNECTING
        _connectionStatus.value = false

        val options = IO.Options.builder()
            .setReconnection(false)    // Gerenciamos reconexão manualmente
            .setTimeout(10_000)
            .setTransports(arrayOf("websocket"))
            .build()

        try {
            socket = IO.socket(URI.create(BuildConfig.BACKEND_URL), options).apply {
                on(Socket.EVENT_CONNECT) { onConnected() }
                on(Socket.EVENT_DISCONNECT) { args -> onDisconnected(args) }
                on(Socket.EVENT_CONNECT_ERROR) { args -> onConnectError(args) }
                
                on("audio_opus_frame") { args ->
                    try {
                        val json = args.firstOrNull() as? JSONObject ?: return@on
                        val frameBase64 = json.optString("frame")
                        val seq = json.optInt("seq", -1)
                        val ts = json.optLong("ts", 0L)
                        if (frameBase64.isNotEmpty()) {
                            val frameBytes = Base64.decode(frameBase64, Base64.NO_WRAP)
                            _opusFrameFlow.tryEmit(OpusFrame(frameBytes, seq, ts))
                            _audioFlow.tryEmit(frameBytes)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro decodificando frame Opus: ${e.message}")
                    }
                }

                on("estado_ia") { args ->
                    val state = args.firstOrNull() as? String ?: "ociosa"
                    _iaStateFlow.value = state
                }

                on("texto_chunk") { args ->
                    val chunk = args.firstOrNull() as? String ?: return@on
                    _textoChunkFlow.tryEmit(chunk)
                }

                on("mensagem_ia") { args ->
                    try {
                        val data = args.firstOrNull() as? JSONObject ?: return@on
                        val message = data.optString("message")
                        val vocabularyJson = data.optJSONArray("vocabulary")
                        val vocabulary = mutableListOf<String>()
                        vocabularyJson?.let {
                            for (i in 0 until it.length()) {
                                vocabulary.add(it.getString(i))
                            }
                        }

                        val mistakesJson = data.optJSONArray("mistakes")
                        val mistakes = mutableListOf<MistakeEntry>()
                        mistakesJson?.let {
                            for (i in 0 until it.length()) {
                                val entry = it.getJSONObject(i)
                                mistakes.add(
                                    MistakeEntry(
                                        wrong = entry.optString("wrong"),
                                        right = entry.optString("right"),
                                        rule = entry.optString("rule"),
                                        raw = entry.optString("raw")
                                    )
                                )
                            }
                        }

                        val sentiment = data.optString("sentiment", "neutral")
                        val sentimentCue = data.optString("sentimentCue")
                        val sentimentConfidence = data.optInt("sentimentConfidence", 50)

                        val bubble = UiChatBubble(
                            message = message,
                            isUser = false,
                            vocabulary = vocabulary,
                            mistakes = mistakes,
                            sentiment = sentiment,
                            sentimentCue = sentimentCue,
                            sentimentConfidence = sentimentConfidence
                        )
                        
                        // Salva resposta final no histórico local
                        val historyObj = JSONObject().apply {
                            put("message", message)
                            put("isUser", false)
                        }
                        currentSession?.messageHistory?.add(historyObj)

                        _mensagemIaFlow.tryEmit(bubble)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao parsear mensagem_ia: ${e.message}")
                    }
                }

                on("session_restored") { args ->
                    Log.d(TAG, "Sessão restaurada no servidor: ${args.firstOrNull()}")
                }

                on("erro_backend") { args ->
                    val err = args.firstOrNull() as? String ?: "Erro no servidor"
                    Log.e(TAG, "Erro do backend: $err")
                    _erroFlow.tryEmit(err)
                }

                connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar socket: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun onConnected() {
        Log.d(TAG, "Conectado ao Socket! Tentativas: $reconnectAttempts")
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.CONNECTED
        _connectionStatus.value = true
        startHeartbeat()

        // Restaura sessão no servidor se houver histórico local
        currentSession?.let { session ->
            val payload = JSONObject().apply {
                put("sessionId", session.sessionId)
                put("isRestore", session.messageHistory.isNotEmpty())
                
                val recentHistory = JSONArray()
                session.messageHistory.takeLast(10).forEach { recentHistory.put(it) }
                put("historySnapshot", recentHistory.toString())
            }
            socket?.emit("restore_session", payload)
        }
    }

    private fun onDisconnected(args: Array<Any>) {
        val reason = args.firstOrNull()?.toString() ?: "desconhecido"
        Log.w(TAG, "Desconectado: $reason")
        stopHeartbeat()
        _connectionStatus.value = false

        if (!isIntentionalDisconnect) {
            _connectionState.value = ConnectionState.RECONNECTING
            if (reason == "io server disconnect") {
                reconnectAttempts = 0
            }
            scheduleReconnect()
        }
    }

    private fun onConnectError(args: Array<Any>) {
        Log.e(TAG, "Erro de conexão: ${args.firstOrNull()}")
        _connectionStatus.value = false
        scheduleReconnect()
    }

    private fun scheduleReconnect(delayMs: Long? = null) {
        if (isIntentionalDisconnect || reconnectAttempts >= MAX_ATTEMPTS) {
            if (reconnectAttempts >= MAX_ATTEMPTS) {
                Log.e(TAG, "Máximo de tentativas de reconexão atingido")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            return
        }

        val backoffDelay = delayMs ?: calculateBackoff()
        reconnectAttempts++
        Log.d(TAG, "Reconectando em ${backoffDelay}ms (tentativa $reconnectAttempts/$MAX_ATTEMPTS)")

        mainHandler.postDelayed({ establishConnection() }, backoffDelay)
    }

    private fun calculateBackoff(): Long {
        val exponential = BASE_DELAY_MS * 2.0.pow(reconnectAttempts).toLong()
        val capped = min(exponential, MAX_DELAY_MS)
        val jitter = (Math.random() * 0.3 * capped).toLong()
        return capped + jitter
    }

    private fun startHeartbeat() {
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    socket?.emit("ping", JSONObject().put("ts", System.currentTimeMillis()))
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    fun iniciarSessao(userId: String) {
        socket?.emit("iniciar_sessao", userId)
    }

    fun enviarMensagem(texto: String) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            val msgObj = JSONObject().apply {
                put("message", texto)
                put("isUser", true)
            }
            currentSession?.messageHistory?.add(msgObj)
            socket?.emit("mensagem_usuario", texto)
        }
    }

    fun sendBargeIn() {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            socket?.emit("barge_in", JSONObject().apply {
                put("sessionId", currentSession?.sessionId ?: "")
                put("timestamp", System.currentTimeMillis())
                put("reason", "user_speech_detected")
            })
        }
    }

    fun sendSpeechEnd(transcript: String, durationMs: Long, vadConfidence: Float) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            socket?.emit("speech_end", JSONObject().apply {
                put("transcript", transcript)
                put("durationMs", durationMs)
                put("vadConfidence", vadConfidence)
            })
        }
    }

    fun usuarioInterrompeu() {
        socket?.emit("usuario_interrompeu")
    }

    private fun registerNetworkCallback() {
        val context = appContext ?: return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        runCatching { cm.registerNetworkCallback(request, networkCallback) }.onFailure { e ->
            Log.e(TAG, "Erro ao registrar callback de rede: ${e.message}", e)
        }
    }

    private fun generateSessionId() = "session_${System.currentTimeMillis()}_${(1000..9999).random()}"

    fun disconnect() {
        isIntentionalDisconnect = true
        stopHeartbeat()
        mainHandler.removeCallbacksAndMessages(null)
        val context = appContext
        if (context != null) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(networkCallback) }
        }
        socket?.disconnect()
        socket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectionStatus.value = false
    }
}
