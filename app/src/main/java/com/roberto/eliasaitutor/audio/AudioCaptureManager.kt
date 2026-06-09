package com.roberto.eliasaitutor.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*

class AudioCaptureManager(
    private val context: Context,
    private val vad: LocalVAD,
    private val onAudioReady: (ByteArray) -> Unit,
    private val enableNoiseSuppression: Boolean = true
) {
    private val sampleRate = 16000
    private val frameSize  = sampleRate * 30 / 1000
    private val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
        frameSize * 4
    )
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val rnnoise = RnnoiseProcessor()

    var onFusedVADUpdate: ((Float) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startCapture() {
        // AudioRecord deve ser criado ANTES de initialize() para que
        // o audioSessionId real esteja disponível para o NoiseSuppressor.
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return
            }
            
            audioRecord?.startRecording()

            if (enableNoiseSuppression) {
                val sessionId = audioRecord?.audioSessionId ?: 0
                rnnoise.initialize(sessionId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            audioRecord?.release()
            audioRecord = null
            return
        }

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(frameSize)
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, frameSize) ?: break
                if (read <= 0) continue
                val raw   = buffer.copyOf(read)
                val clean = if (enableNoiseSuppression) rnnoise.process(raw) else raw
                if (clean.isEmpty()) continue
                vad.processFrame(clean)
                if (enableNoiseSuppression) {
                    val fused = maxOf(vad.currentVolumeLevel, rnnoise.lastVADProbability * 0.9f)
                    onFusedVADUpdate?.invoke(fused)
                } else {
                    onFusedVADUpdate?.invoke(vad.currentVolumeLevel)
                }
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        if (enableNoiseSuppression) rnnoise.release()
        vad.reset()
    }
    
    fun release() {
        stopCapture()
    }
}
