package com.roberto.eliasaitutor.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Main chat TTS player (Task Final v1.0).
 *
 * Spec mentioned ExoPlayer + MP3 stream; production path is lower-latency:
 * ElevenLabs PCM → backend Opus frames → MediaCodec decode → AudioTrack.
 * Do not replace with ExoPlayer for chat — it would break barge-in / jitter / Opus.
 */
class OpusAudioPlayer {
    companion object {
        private const val TAG = "OpusAudioPlayer"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 1
        private const val FRAME_SIZE = 960 // 20ms @ 48kHz Mono
    }

    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var decoder: MediaCodec? = null
    
    private val jitterBuffer = JitterBuffer()
    private val plcGenerator = PLCGenerator()
    
    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playJob: Job? = null
    @Volatile private var isPlaying = false
    /** Backend finished sending frames — keep playing until jitter buffer drains. */
    @Volatile private var streamEnded = false
    private var consecutiveEmptyFrames = 0

    var audioSessionId: Int = 0
        private set

    init {
        initAudioTrack()
        initDecoder()
    }

    private fun initAudioTrack() {
        try {
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
            // Larger buffer reduces underruns that sound like choking / cut phrases
            val bufferSize = maxOf(minBuf * 4, FRAME_SIZE * 2 * 24) // ~480ms
            
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            
            audioSessionId = audioTrack?.audioSessionId ?: 0
            if (audioSessionId != 0 && AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)
                echoCanceler?.enabled = true
                Log.d(TAG, "AcousticEchoCanceler habilitado no player")
            }
            
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar AudioTrack: ${e.message}")
        }
    }

    private fun initDecoder() {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS)
            
            // Opus CSD (Codec Specific Data) Headers
            // csd-0: identification header
            val csd0 = byteArrayOf(
                'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
                'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
                1, // Version
                CHANNELS.toByte(), // Channel count
                0x00, 0x0F.toByte(), // Pre-skip (3840 samples)
                0x80.toByte(), 0xBB.toByte(), 0x00, 0x00, // Sample rate (48000)
                0, 0, // Output gain
                0 // Mapping family
            )
            
            // csd-1: pre-skip in nanoseconds
            val preSkipNs = 3840L * 1_000_000_000L / SAMPLE_RATE
            val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(preSkipNs).apply { flip() }
            
            // csd-2: seek pre-roll in nanoseconds
            val preRollNs = 80_000_000L // 80ms
            val csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(preRollNs).apply { flip() }

            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            format.setByteBuffer("csd-1", csd1)
            format.setByteBuffer("csd-2", csd2)

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            decoder?.configure(format, null, null, 0)
            decoder?.start()
            Log.d(TAG, "MediaCodec decodificador Opus iniciado")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar decodificador: ${e.message}")
        }
    }

    fun startPlayout() {
        if (isPlaying) {
            streamEnded = false
            consecutiveEmptyFrames = 0
            return
        }
        isPlaying = true
        streamEnded = false
        consecutiveEmptyFrames = 0
        jitterBuffer.clear()
        plcGenerator.clear()

        playJob = playerScope.launch { playoutLoop() }
        Log.d(TAG, "Playout coroutine iniciada")
    }

    /**
     * Backend finished the TTS stream (estado_ia=ociosa).
     * Do NOT flush immediately — play remaining jitter-buffer frames so the
     * last words of the phrase are not cut off.
     */
    fun markStreamEnded() {
        streamEnded = true
        Log.d(TAG, "Stream ended — draining jitter buffer (size=${jitterBuffer.getBufferSize()})")
    }

    /** Hard stop (barge-in / error / voice switch). Drops buffered audio. */
    fun stopPlayout() {
        isPlaying = false
        streamEnded = false
        consecutiveEmptyFrames = 0
        playJob?.cancel()
        playJob = null
        jitterBuffer.clear()
        plcGenerator.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao resetar AudioTrack: ${e.message}")
        }
    }

    fun handleIncomingOpusFrame(data: ByteArray, seq: Int, timestampMs: Long) {
        streamEnded = false
        consecutiveEmptyFrames = 0
        if (!isPlaying) startPlayout()
        jitterBuffer.addPacket(data, seq, timestampMs)
    }

    private suspend fun playoutLoop() {
        val info = MediaCodec.BufferInfo()
        val tempShortArray = ShortArray(FRAME_SIZE)

        while (currentCoroutineContext().isActive && isPlaying) {
            val startTime = System.currentTimeMillis()
            
            // 1. Get next packet from JitterBuffer
            val opusFrame = jitterBuffer.getNextFrame(startTime)
            
            if (opusFrame != null) {
                consecutiveEmptyFrames = 0
                // Dequeue input buffer, feed it to decoder
                val decoderInstance = decoder
                if (decoderInstance != null) {
                    try {
                        val inputIndex = decoderInstance.dequeueInputBuffer(5000) // 5ms timeout
                        if (inputIndex >= 0) {
                            val inputBuffer = decoderInstance.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                inputBuffer.put(opusFrame)
                                decoderInstance.queueInputBuffer(inputIndex, 0, opusFrame.size, startTime * 1000, 0)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro no queueInputBuffer: ${e.message}")
                    }
                }
            } else {
                consecutiveEmptyFrames++
                // Mid-stream underflow: light PLC only while still expecting packets
                if (!streamEnded &&
                    (jitterBuffer.getBufferSize() > 0 || jitterBuffer.packetLossCount > 0)
                ) {
                    val plcFrame = plcGenerator.generateConcealmentFrame()
                    writePcmToTrack(plcFrame)
                }
                // After backend ends: stop once buffer is drained (~300ms empty)
                if (streamEnded &&
                    jitterBuffer.getBufferSize() == 0 &&
                    consecutiveEmptyFrames >= 15
                ) {
                    Log.d(TAG, "Drain complete — stopping playout")
                    isPlaying = false
                    break
                }
            }

            // Dequeue output buffer from decoder if available
            val decoderInstance = decoder
            if (decoderInstance != null) {
                try {
                    var outputIndex = decoderInstance.dequeueOutputBuffer(info, 0)
                    while (outputIndex >= 0) {
                        val outputBuffer = decoderInstance.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            
                            val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val samplesRead = shortBuffer.remaining()
                            
                            if (samplesRead == FRAME_SIZE) {
                                shortBuffer.get(tempShortArray)
                                plcGenerator.updateLastValidFrame(tempShortArray)
                                writePcmToTrack(tempShortArray)
                            } else if (samplesRead > 0) {
                                // If not exactly 20ms, write whatever we got
                                val partialShorts = ShortArray(samplesRead)
                                shortBuffer.get(partialShorts)
                                writePcmToTrack(partialShorts)
                            }
                        }
                        decoderInstance.releaseOutputBuffer(outputIndex, false)
                        outputIndex = decoderInstance.dequeueOutputBuffer(info, 0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no dequeueOutputBuffer: ${e.message}")
                }
            }

            // Playout rate pacing: keep exactly 20ms interval
            val elapsed = System.currentTimeMillis() - startTime
            val sleepTime = 20L - elapsed
            if (sleepTime > 0) {
                delay(sleepTime) // coroutine delay: suspensível, não bloqueia thread, sem drift
            }
        }
        playJob = null
    }

    private fun writePcmToTrack(pcmData: ShortArray) {
        val track = audioTrack ?: return
        try {
            // BLOCKING avoids silent drops that sound like choking / cut words
            track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao escrever no AudioTrack: ${e.message}")
        }
    }

    fun getJitterStats(): JitterStats {
        return JitterStats(
            jitterMs = jitterBuffer.getJitterMs(),
            targetDelayMs = jitterBuffer.getTargetDelayMs(),
            bufferSize = jitterBuffer.getBufferSize(),
            packetLoss = jitterBuffer.packetLossCount,
            latePackets = jitterBuffer.latePacketsCount,
            underflows = jitterBuffer.bufferUnderflowCount
        )
    }

    fun release() {
        stopPlayout()
        playerScope.cancel() // cancela todas as coroutines pendentes
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar decoder: ${e.message}")
        }
        decoder = null

        try {
            echoCanceler?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar echoCanceler: ${e.message}")
        }
        echoCanceler = null

        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar audioTrack: ${e.message}")
        }
        audioTrack = null
    }
}

data class JitterStats(
    val jitterMs: Long,
    val targetDelayMs: Long,
    val bufferSize: Int,
    val packetLoss: Int,
    val latePackets: Int,
    val underflows: Int
)
