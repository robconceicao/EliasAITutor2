package com.roberto.eliasaitutor.audio

import android.util.Log
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class JitterBuffer {
    data class Packet(
        val data: ByteArray,
        val seq: Int,
        val timestampMs: Long,
        val arrivalTimeMs: Long
    ) : Comparable<Packet> {
        override fun compareTo(other: Packet): Int = this.seq.compareTo(other.seq)
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Packet
            return seq == other.seq
        }

        override fun hashCode(): Int = seq
    }

    private val queue = PriorityQueue<Packet>()
    
    // RFC 3550 Jitter estimation
    private var lastTransit: Long = 0
    private var jitter: Double = 0.0
    
    // Target delay adaptation
    private var targetDelayMs: Long = 60 // Starts at 3 frames (60ms)
    private val frameDurationMs = 20L
    private var playoutStarted = false
    private var playoutStartSystemTime: Long = 0
    private var playoutStartPacketSeq: Int = -1
    private var nextExpectedSeq: Int = -1
    
    // Statistics
    var packetLossCount = 0
        private set
    var latePacketsCount = 0
        private set
    var receivedPacketsCount = 0
        private set
    var bufferUnderflowCount = 0
        private set

    @Synchronized
    fun addPacket(data: ByteArray, seq: Int, packetTimestampMs: Long) {
        val now = System.currentTimeMillis()
        receivedPacketsCount++

        // Calculate RFC 3550 Jitter
        if (lastTransit != 0L) {
            val transit = now - packetTimestampMs
            val d = transit - lastTransit
            jitter = jitter + (abs(d.toDouble()) - jitter) / 16.0
        }
        lastTransit = now - packetTimestampMs

        // Dynamically adjust target delay based on jitter
        // We target: delay = max(3 * frameDuration, 2 * jitter)
        val calculatedDelay = max(60L, (2.0 * jitter).toLong())
        // Cap target delay between 60ms and 300ms
        targetDelayMs = min(300L, max(60L, calculatedDelay))

        // If the packet is older than what we are expecting, it's late / discard
        if (nextExpectedSeq != -1 && seq < nextExpectedSeq) {
            latePacketsCount++
            return
        }

        val packet = Packet(data, seq, packetTimestampMs, now)
        if (!queue.contains(packet)) {
            queue.add(packet)
        }
    }

    @Synchronized
    fun getNextFrame(currentPlayoutTimeMs: Long): ByteArray? {
        if (queue.isEmpty()) {
            if (playoutStarted) {
                bufferUnderflowCount++
            }
            return null
        }

        val now = System.currentTimeMillis()

        // Playout control
        if (!playoutStarted) {
            // Buffer at least targetDelayMs worth of packets before starting
            val oldestPacket = queue.peek() ?: return null
            val bufferedDuration = now - oldestPacket.arrivalTimeMs
            if (bufferedDuration < targetDelayMs) {
                return null // Keep buffering
            }
            playoutStarted = true
            playoutStartSystemTime = now
            playoutStartPacketSeq = oldestPacket.seq
            nextExpectedSeq = oldestPacket.seq
        }

        // Check the head of the queue
        val head = queue.peek()
        if (head != null) {
            if (head.seq == nextExpectedSeq) {
                queue.poll()
                nextExpectedSeq++
                return head.data
            } else if (head.seq < nextExpectedSeq) {
                // Should not happen as we discard old packets in addPacket,
                // but just in case, discard it now
                queue.poll()
                return getNextFrame(currentPlayoutTimeMs)
            } else {
                // Gap detected! head.seq > nextExpectedSeq.
                // How long has it been since we played the last frame?
                // If the playout time has elapsed beyond a certain threshold,
                // we treat the packet as lost and trigger PLC.
                // Playout expected time for nextExpectedSeq:
                val elapsedSincePlayoutStart = now - playoutStartSystemTime
                val expectedFrameIndex = nextExpectedSeq - playoutStartPacketSeq
                val expectedPlayoutTime = expectedFrameIndex * frameDurationMs
                
                // Allow a small grace period (e.g., 20ms) before declaring packet lost
                if (elapsedSincePlayoutStart > expectedPlayoutTime + 20L) {
                    packetLossCount++
                    nextExpectedSeq++ // Skip this packet and trigger PLC (return null)
                    Log.w("JitterBuffer", "Packet loss detected. Expected seq: ${nextExpectedSeq - 1}, next in queue: ${head.seq}")
                    return null
                } else {
                    // Still waiting for the packet within playout time
                    return null
                }
            }
        }

        return null
    }

    @Synchronized
    fun clear() {
        queue.clear()
        lastTransit = 0
        jitter = 0.0
        targetDelayMs = 60
        playoutStarted = false
        nextExpectedSeq = -1
        playoutStartSystemTime = 0   // fix: evita cálculo errado de expectedPlayoutTime após barge-in
        playoutStartPacketSeq = -1   // fix: evita herdar seq de sessão de áudio anterior
        packetLossCount = 0          // fix: zera estatísticas da sessão anterior
        latePacketsCount = 0
        receivedPacketsCount = 0
        bufferUnderflowCount = 0
    }

    fun getJitterMs(): Long = jitter.toLong()
    fun getTargetDelayMs(): Long = targetDelayMs
    fun getBufferSize(): Int = queue.size
}
