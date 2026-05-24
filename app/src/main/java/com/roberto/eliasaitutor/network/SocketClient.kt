package com.roberto.eliasaitutor.network

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
import org.json.JSONObject
import java.net.URISyntaxException

object SocketClient {
    private const val TAG = "SocketClient"
    private var socket: Socket? = null

    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus

    private val _audioFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFlow: SharedFlow<ByteArray> = _audioFlow

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

    fun connect() {
        if (socket != null) return
        try {
            Log.d(TAG, "Tentando conectar ao BACKEND_URL: ${BuildConfig.BACKEND_URL}")
            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
            }
            socket = IO.socket(BuildConfig.BACKEND_URL, opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ Conectado ao backend Socket.io")
                _connectionStatus.value = true
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "❌ Desconectado do backend")
                _connectionStatus.value = false
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val err = args.firstOrNull()?.toString() ?: "Erro desconhecido"
                Log.e(TAG, "Erro de conexão: $err")
                _connectionStatus.value = false
            }

            socket?.on("audio_chunk") { args ->
                try {
                    val base64Data = args.firstOrNull() as? String ?: return@on
                    if (base64Data.isNotEmpty()) {
                        val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                        _audioFlow.tryEmit(pcmBytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao decodificar áudio: ${e.message}")
                }
            }

            socket?.on("estado_ia") { args ->
                val state = args.firstOrNull() as? String ?: "ociosa"
                _iaStateFlow.value = state
            }

            socket?.on("erro_backend") { args ->
                val err = args.firstOrNull() as? String ?: "Erro no servidor"
                Log.e(TAG, "Erro do backend: $err")
                _erroFlow.tryEmit(err)
            }

            socket?.on("texto_chunk") { args ->
                val chunk = args.firstOrNull() as? String ?: return@on
                _textoChunkFlow.tryEmit(chunk)
            }

            socket?.on("mensagem_ia") { args ->
                try {
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    
                    val message = data.optString("message")
                    val vocabularyJson = data.optJSONArray("vocabulary")
                    val vocabulary = mutableListOf<String>()
                    if (vocabularyJson != null) {
                        for (i in 0 until vocabularyJson.length()) {
                            vocabulary.add(vocabularyJson.getString(i))
                        }
                    }
                    
                    val mistakesJson = data.optJSONArray("mistakes")
                    val mistakes = mutableListOf<MistakeEntry>()
                    if (mistakesJson != null) {
                        for (i in 0 until mistakesJson.length()) {
                            val entry = mistakesJson.getJSONObject(i)
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
                    _mensagemIaFlow.tryEmit(bubble)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao parsear mensagem_ia: ${e.message}")
                }
            }

            socket?.connect()
        } catch (e: URISyntaxException) {
            Log.e(TAG, "URL inválida: ${e.message}")
        }
    }

    fun iniciarSessao(userId: String) {
        socket?.emit("iniciar_sessao", userId)
    }

    fun enviarMensagem(texto: String) {
        socket?.emit("mensagem_usuario", texto)
    }

    fun usuarioInterrompeu() {
        socket?.emit("usuario_interrompeu")
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        _connectionStatus.value = false
    }
}
