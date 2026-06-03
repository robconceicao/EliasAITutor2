package com.roberto.eliasaitutor.audio

import android.util.Log
import com.roberto.eliasaitutor.network.SocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackState { IDLE, AI_SPEAKING, BARGED_IN, USER_SPEAKING }

class BargeInController(
    private val audioPlayer: OpusAudioPlayer,
    // jitterBuffer and socketClient removed from constructor since they are global in the ViewModel or Singletons
    private val onStateChange: (PlaybackState) -> Unit
) {
    companion object {
        private const val TAG               = "BargeInController"
        private const val BARGE_IN_DEBOUNCE = 300L
        private const val MIN_AI_SPEECH_MS  = 400L
    }

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _state

    private var lastBargeInTime    = 0L
    private var aiSpeechStartTime  = 0L
    private var bargeInJob: Job?   = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun onAISpeechStarted() {
        aiSpeechStartTime = System.currentTimeMillis()
        _state.value = PlaybackState.AI_SPEAKING; onStateChange(PlaybackState.AI_SPEAKING)
    }

    fun onUserBeginsSpeech() {
        val now = System.currentTimeMillis()
        if (now - lastBargeInTime < BARGE_IN_DEBOUNCE) return
        if (_state.value != PlaybackState.AI_SPEAKING) return
        if (now - aiSpeechStartTime < MIN_AI_SPEECH_MS) return
        lastBargeInTime = now; executeBargeIn()
    }

    fun onUserEndsSpeech() {
        if (_state.value == PlaybackState.BARGED_IN || _state.value == PlaybackState.USER_SPEAKING) {
            _state.value = PlaybackState.USER_SPEAKING; onStateChange(PlaybackState.USER_SPEAKING)
        }
    }

    fun onAIResponseReady() {
        if (_state.value == PlaybackState.USER_SPEAKING) {
            _state.value = PlaybackState.AI_SPEAKING
            aiSpeechStartTime = System.currentTimeMillis()
            onStateChange(PlaybackState.AI_SPEAKING)
            // audioPlayer.resume() - OpusAudioPlayer handles playout automatically when receiving frames
        }
    }

    fun onAISpeechFinished() {
        _state.value = PlaybackState.IDLE; onStateChange(PlaybackState.IDLE)
    }

    private fun executeBargeIn() {
        bargeInJob?.cancel()
        bargeInJob = scope.launch {
            _state.value = PlaybackState.BARGED_IN; onStateChange(PlaybackState.BARGED_IN)
            audioPlayer.stopPlayout() // The current OpusAudioPlayer has stopPlayout() instead of stopForBargeIn()
            SocketClient.sendBargeIn() // Calls the global SocketClient
        }
    }

    fun reset() { bargeInJob?.cancel(); _state.value = PlaybackState.IDLE; lastBargeInTime = 0L }
    fun release() { reset(); scope.cancel() }
}
