package com.roberto.eliasaitutor.network

import android.util.Base64
import com.roberto.eliasaitutor.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID

object CartesiaClient {
    private val client = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isConnecting = false

    val isConnected: Boolean get() = webSocket != null

    // SharedFlow to emit audio chunks as they arrive
    private val _audioFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFlow: SharedFlow<ByteArray> = _audioFlow

    fun connect() {
        if (webSocket != null || isConnecting) return
        val apiKey = BuildConfig.CARTESIA_API_KEY
        if (apiKey.isBlank()) return
        isConnecting = true
        val url = "wss://api.cartesia.ai/tts/websocket?api_key=$apiKey&cartesia_version=2024-06-10"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                isConnecting = false
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "chunk" -> {
                            val base64Data = obj.optString("data")
                            if (base64Data.isNotEmpty()) {
                                val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                _audioFlow.tryEmit(pcmBytes)
                            }
                        }
                        "error" -> android.util.Log.e("CartesiaClient", "API error: $text")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@CartesiaClient.webSocket = null
                isConnecting = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                this@CartesiaClient.webSocket = null
                isConnecting = false
                android.util.Log.e("CartesiaClient", "WebSocket failure: ${t.message}")
                // Reconnect after 3 seconds
                scope.launch {
                    delay(3_000)
                    connect()
                }
            }
        })
    }

    fun sendChunk(text: String, isLast: Boolean, contextId: String) {
        val ws = webSocket ?: return
        try {
            val json = JSONObject().apply {
                put("context_id", contextId)
                put("model_id", "sonic-english")
                put("transcript", text)
                put("continue", !isLast)
                put("voice", JSONObject().apply {
                    put("mode", "id")
                    put("id", "a0e99841-438c-4a64-b679-ae501e7d6091") // Default nice English voice
                })
                put("output_format", JSONObject().apply {
                    put("container", "raw")
                    put("encoding", "pcm_f32le")
                    put("sample_rate", 44100)
                })
            }
            ws.send(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
