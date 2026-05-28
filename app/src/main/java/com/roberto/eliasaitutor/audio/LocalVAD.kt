package com.roberto.eliasaitutor.audio

import kotlin.math.sqrt

class LocalVAD(
    private val thresholdRms: Double = 500.0, // RMS amplitude threshold (0..32767)
    private val speechConfirmMs: Long = 100,  // minimum duration above threshold to confirm speech
    private val silenceConfirmMs: Long = 800  // hangover time: duration below threshold to confirm silence
) {
    enum class State {
        SILENCE, SPEECH
    }

    private var currentState = State.SILENCE
    private var lastStateChangeTimeMs = System.currentTimeMillis()
    
    // For callback notification
    private var listener: VADListener? = null
    
    interface VADListener {
        fun onSpeechDetected()
        fun onSilenceDetected()
        fun onRmsChanged(rms: Float)
    }

    fun setListener(listener: VADListener) {
        this.listener = listener
    }

    /**
     * Processes a chunk of PCM Short samples and returns current VAD state.
     */
    fun processSamples(samples: ShortArray): State {
        var sum = 0.0
        for (sample in samples) {
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = if (samples.isNotEmpty()) sqrt(sum / samples.size) else 0.0
        
        // Notify listener of RMS value for visualizer/feedback
        listener?.onRmsChanged(rms.toFloat())

        val now = System.currentTimeMillis()
        val isAboveThreshold = rms > thresholdRms

        when (currentState) {
            State.SILENCE -> {
                if (isAboveThreshold) {
                    val durationAbove = now - lastStateChangeTimeMs
                    if (durationAbove >= speechConfirmMs) {
                        currentState = State.SPEECH
                        lastStateChangeTimeMs = now
                        listener?.onSpeechDetected()
                    }
                } else {
                    lastStateChangeTimeMs = now // Reset speech timer
                }
            }
            State.SPEECH -> {
                if (!isAboveThreshold) {
                    val durationBelow = now - lastStateChangeTimeMs
                    if (durationBelow >= silenceConfirmMs) {
                        currentState = State.SILENCE
                        lastStateChangeTimeMs = now
                        listener?.onSilenceDetected()
                    }
                } else {
                    lastStateChangeTimeMs = now // Reset silence timer (hangover)
                }
            }
        }

        return currentState
    }

    fun reset() {
        currentState = State.SILENCE
        lastStateChangeTimeMs = System.currentTimeMillis()
    }
}
