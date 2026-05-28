package com.roberto.eliasaitutor.audio

import kotlin.math.max

class PLCGenerator {
    private var lastValidFrame: ShortArray? = null
    private var attenuationFactor = 1.0
    private val frameSize = 960 // 20ms @ 48kHz Mono

    fun updateLastValidFrame(pcmFrame: ShortArray) {
        if (pcmFrame.size == frameSize) {
            if (lastValidFrame == null || lastValidFrame!!.size != frameSize) {
                lastValidFrame = ShortArray(frameSize)
            }
            System.arraycopy(pcmFrame, 0, lastValidFrame!!, 0, frameSize)
            attenuationFactor = 1.0 // Reset attenuation
        }
    }

    fun generateConcealmentFrame(): ShortArray {
        val frame = ShortArray(frameSize)
        val last = lastValidFrame
        if (last != null) {
            // Attenuate the last valid frame to fade out smoothly and avoid clicks
            attenuationFactor = max(0.0, attenuationFactor - 0.2) // decrease amplitude by 20% each step
            for (i in 0 until frameSize) {
                frame[i] = (last[i] * attenuationFactor).toInt().toShort()
            }
            // Update last valid frame with the attenuated version for successive losses
            System.arraycopy(frame, 0, last, 0, frameSize)
        }
        return frame
    }

    fun clear() {
        lastValidFrame = null
        attenuationFactor = 1.0
    }
}
