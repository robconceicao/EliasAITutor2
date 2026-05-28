package com.roberto.eliasaitutor.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioCaptureManager(
    private val context: Context,
    private val localVad: LocalVAD
) {
    companion object {
        private const val TAG = "AudioCaptureManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_MS = 30 // Process every 30ms
        private const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000 // 480 samples
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRecording) return
        
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize, SAMPLES_PER_FRAME * 2 * 4) // 4 frames min buffer
            
            // Use VOICE_COMMUNICATION to enable hardware Echo Cancellation (AEC)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Falha ao inicializar AudioRecord")
                return
            }

            isRecording = true
            audioRecord?.startRecording()
            
            recordThread = Thread({ captureLoop() }, "AudioCaptureThread").apply { start() }
            Log.d(TAG, "Captura de áudio iniciada")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar captura de áudio: ${e.message}")
        }
    }

    fun stopCapture() {
        if (!isRecording) return
        isRecording = false
        recordThread?.interrupt()
        recordThread = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar AudioRecord: ${e.message}")
        }
        audioRecord = null
        Log.d(TAG, "Captura de áudio parada")
    }

    private fun captureLoop() {
        val buffer = ShortArray(SAMPLES_PER_FRAME)
        
        while (isRecording) {
            val record = audioRecord ?: break
            val readSamples = record.read(buffer, 0, SAMPLES_PER_FRAME)
            
            if (readSamples > 0) {
                // If we read less than expected, only process what we got
                val samplesToProcess = if (readSamples == SAMPLES_PER_FRAME) {
                    buffer
                } else {
                    buffer.copyOfRange(0, readSamples)
                }
                
                localVad.processSamples(samplesToProcess)
            }
            
            // Pace the thread loop to match recording rate
            try {
                Thread.sleep(FRAME_MS.toLong())
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    fun release() {
        stopCapture()
    }
}
