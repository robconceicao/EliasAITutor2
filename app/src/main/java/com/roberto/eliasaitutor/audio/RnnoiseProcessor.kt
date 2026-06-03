package com.roberto.eliasaitutor.audio

import com.github.wiryls.rnnoise.RNNoise

class RnnoiseProcessor {
    companion object {
        private const val TAG        = "RnnoiseProcessor"
        const val FRAME_SIZE         = 160  // 10ms @ 16kHz
    }

    private var denoiser: RNNoise? = null
    private val overflow = ArrayDeque<Short>()

    var lastVADProbability: Float = 0f
        private set

    fun initialize() { denoiser = RNNoise() }

    fun process(input: ShortArray): ShortArray {
        val d = denoiser ?: return input
        overflow.addAll(input.toList())
        val out = mutableListOf<Short>()
        while (overflow.size >= FRAME_SIZE) {
            val frame = ShortArray(FRAME_SIZE) { overflow.removeFirst() }
            val floats = FloatArray(FRAME_SIZE) { i -> frame[i] / 32768f }
            lastVADProbability = d.processFrame(floats)
            out.addAll(ShortArray(FRAME_SIZE) { i ->
                (floats[i] * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }.toList())
        }
        return out.toShortArray()
    }

    fun release() { denoiser?.destroy(); denoiser = null; overflow.clear() }
}
