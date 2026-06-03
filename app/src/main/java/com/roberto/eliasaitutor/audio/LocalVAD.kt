package com.roberto.eliasaitutor.audio

import kotlin.math.sqrt

class LocalVAD(
    private val sampleRate: Int = 16000,
    private val onSpeechStart: () -> Unit,
    private val onSpeechEnd: (ByteArray) -> Unit
) {
    companion object {
        private const val RMS_SPEECH_THRESHOLD  = 0.015f  // calibrado para áudio pós-RNNoise
        private const val RMS_SILENCE_THRESHOLD = 0.007f
        private const val SPEECH_CONFIRM_FRAMES  = 3
        private const val SILENCE_CONFIRM_FRAMES = 25
        private const val FRAME_SIZE_MS          = 30
        private const val MAX_BUFFER_SECONDS     = 30
    }

    enum class VADState { IDLE, SPEECH, TRAILING_SILENCE }

    private var state = VADState.IDLE
    private var consecutiveSpeechFrames  = 0
    private var consecutiveSilenceFrames = 0
    private val audioBuffer = mutableListOf<Short>()
    private val maxBufferSamples = sampleRate * MAX_BUFFER_SECONDS

    var currentVolumeLevel = 0f
        private set

    fun processFrame(samples: ShortArray) {
        val rms = calculateRMS(samples)
        currentVolumeLevel = (rms / 0.1f).coerceIn(0f, 1f)

        when (state) {
            VADState.IDLE -> {
                if (rms >= RMS_SPEECH_THRESHOLD) {
                    consecutiveSpeechFrames++
                    audioBuffer.addAll(samples.toList())
                    if (consecutiveSpeechFrames >= SPEECH_CONFIRM_FRAMES) {
                        state = VADState.SPEECH; consecutiveSilenceFrames = 0
                        onSpeechStart()
                    }
                } else {
                    consecutiveSpeechFrames = 0
                    audioBuffer.addAll(samples.toList())
                    val maxPreroll = sampleRate * 3 / 10
                    if (audioBuffer.size > maxPreroll)
                        audioBuffer.subList(0, audioBuffer.size - maxPreroll).clear()
                }
            }
            VADState.SPEECH -> {
                audioBuffer.addAll(samples.toList())
                if (audioBuffer.size > maxBufferSamples)
                    audioBuffer.subList(0, audioBuffer.size - maxBufferSamples).clear()
                if (rms < RMS_SILENCE_THRESHOLD) {
                    consecutiveSilenceFrames++; state = VADState.TRAILING_SILENCE
                } else consecutiveSilenceFrames = 0
            }
            VADState.TRAILING_SILENCE -> {
                audioBuffer.addAll(samples.toList())
                if (rms >= RMS_SPEECH_THRESHOLD) {
                    state = VADState.SPEECH; consecutiveSilenceFrames = 0; return
                }
                consecutiveSilenceFrames++
                if (consecutiveSilenceFrames >= SILENCE_CONFIRM_FRAMES) {
                    val finalAudio = shortArrayToByteArray(audioBuffer.toShortArray())
                    audioBuffer.clear(); state = VADState.IDLE
                    consecutiveSpeechFrames = 0; consecutiveSilenceFrames = 0
                    currentVolumeLevel = 0f
                    onSpeechEnd(finalAudio)
                }
            }
        }
    }

    private fun calculateRMS(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) { val n = s / 32768.0; sum += n * n }
        return sqrt(sum / samples.size).toFloat()
    }

    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2]     = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    fun reset() {
        state = VADState.IDLE; audioBuffer.clear()
        consecutiveSpeechFrames = 0; consecutiveSilenceFrames = 0; currentVolumeLevel = 0f
    }
}
