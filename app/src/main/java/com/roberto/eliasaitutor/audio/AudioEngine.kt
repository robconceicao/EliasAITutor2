package com.roberto.eliasaitutor.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class AudioEngine(private val context: Context) {

    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // Event flows for ViewModel
    private val _userSpeechStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val userSpeechStarted: SharedFlow<Unit> = _userSpeechStarted

    private val _userSpeechResult = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userSpeechResult: SharedFlow<String> = _userSpeechResult

    init {
        initAudioTrack()
        initSpeechRecognizer()
    }

    private fun initAudioTrack() {
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_FLOAT
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            val audioSessionId = audioTrack?.audioSessionId ?: return
            
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)
                echoCanceler?.enabled = true
            }

            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            
            override fun onBeginningOfSpeech() {
                // Barge-in detected
                _userSpeechStarted.tryEmit(Unit)
                flushAudio()
            }

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // Can retry or emit error, skipping for now
                Log.e("AudioEngine", "STT Error: $error")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    _userSpeechResult.tryEmit(matches[0])
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }
    
    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun playPcmData(data: ByteArray) {
        val track = audioTrack ?: return
        try {
            val floatArray = ByteBuffer.wrap(data)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
            val floats = FloatArray(floatArray.remaining())
            floatArray.get(floats)
            
            track.write(floats, 0, floats.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun flushAudio() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        speechRecognizer?.destroy()
        echoCanceler?.release()
        audioTrack?.release()
    }
}
