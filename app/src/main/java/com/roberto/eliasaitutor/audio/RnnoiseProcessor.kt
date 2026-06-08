package com.roberto.eliasaitutor.audio

import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.abs

/**
 * Supressor de ruído para o pipeline de captura de áudio do Elias.
 *
 * Estratégia em duas camadas:
 *  1. [NoiseSuppressor] da Android AudioEffect API (WebRTC embutido no hardware)
 *     — ativado automaticamente quando o AudioRecord session ID é fornecido.
 *  2. Spectral gating adaptativo em software (fallback) — sem dependências nativas
 *     externas, funciona em qualquer dispositivo Android.
 *
 * FRAME_SIZE = 160 samples (10ms @ 16 kHz) — compatível com o frame do VAD local.
 */
class RnnoiseProcessor {
    companion object {
        private const val TAG       = "RnnoiseProcessor"
        const val FRAME_SIZE        = 160     // 10ms @ 16 kHz
        private const val SMOOTHING = 0.85f   // suavização exponencial da energia
        // Fator de gate: amostras abaixo de (noise floor × GATE_FACTOR) são silenciadas
        private const val GATE_FACTOR = 1.5f
        // Taxa de aprendizado do noise floor: rápida em silêncio, lenta em fala
        private const val LEARN_RATE_SILENCE = 0.05f
        private const val LEARN_RATE_SPEECH  = 0.005f
        // Limiar de energia (short²) abaixo do qual o frame é considerado silêncio
        private const val SILENCE_ENERGY_THRESHOLD = 80_000f
    }

    /** Probabilidade estimada de fala no último frame processado — range [0.0, 1.0]. */
    var lastVADProbability: Float = 0f
        private set

    private var noiseSuppressor: NoiseSuppressor? = null

    // Perfil de ruído adaptativo por banda (metade do frame — domínio simplificado)
    private val noiseProfile = FloatArray(FRAME_SIZE / 2) { 300f }
    private var smoothedEnergy = 0f

    /**
     * Inicializa o [NoiseSuppressor] de hardware quando disponível.
     *
     * @param audioSessionId session ID do [android.media.AudioRecord] — necessário para
     *   associar o effect ao stream de captura. Valor 0 força o fallback por software.
     */
    fun initialize(audioSessionId: Int = 0) {
        if (audioSessionId != 0 && NoiseSuppressor.isAvailable()) {
            runCatching {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "NoiseSuppressor hardware habilitado (sessionId=$audioSessionId)")
            }.onFailure { e ->
                Log.w(TAG, "NoiseSuppressor indisponível: ${e.message} — usando spectral gating")
                noiseSuppressor = null
            }
        } else {
            val reason = if (audioSessionId == 0) "sessionId=0" else "hardware ausente"
            Log.d(TAG, "NoiseSuppressor hardware não inicializado ($reason) — usando spectral gating")
        }
    }

    /**
     * Processa um frame de áudio PCM 16-bit @ 16 kHz.
     *
     * Quando o [NoiseSuppressor] de hardware está ativo, o [android.media.AudioRecord]
     * já entrega o áudio filtrado — apenas calculamos a probabilidade de VAD.
     * Caso contrário, aplica spectral gating em software antes de calcular o VAD.
     *
     * @param input frame de áudio bruto (ou pré-filtrado pelo hardware)
     * @return frame com ruído reduzido
     */
    fun process(input: ShortArray): ShortArray {
        val output = if (noiseSuppressor?.enabled == true) {
            // Hardware já filtrou na captura — apenas propaga o frame
            input
        } else {
            applySpectralGating(input)
        }
        updateVADProbability(output)
        return output
    }

    /**
     * Spectral gating adaptativo:
     * — Estima o noise floor por banda usando exponential moving average.
     * — Zera amostras abaixo de (noise floor × GATE_FACTOR).
     */
    private fun applySpectralGating(input: ShortArray): ShortArray {
        val output = input.copyOf()

        // Calcula energia média do frame para decidir a taxa de aprendizado
        var sumSq = 0.0
        for (s in input) sumSq += s.toDouble() * s
        val frameEnergy = (sumSq / input.size).toFloat()
        val isSilent = frameEnergy < SILENCE_ENERGY_THRESHOLD
        val learningRate = if (isSilent) LEARN_RATE_SILENCE else LEARN_RATE_SPEECH

        // Atualiza noise floor por banda e aplica gate
        for (i in output.indices) {
            val band = (i / 2).coerceAtMost(noiseProfile.size - 1)
            val amplitude = abs(input[i].toFloat())

            // Atualiza estimativa do ruído para esta banda
            noiseProfile[band] = noiseProfile[band] * (1f - learningRate) + amplitude * learningRate

            // Silencia amostras abaixo do limiar adaptativo
            if (amplitude < noiseProfile[band] * GATE_FACTOR) {
                output[i] = 0
            }
        }
        return output
    }

    /** Atualiza [lastVADProbability] usando energia suavizada do frame filtrado. */
    private fun updateVADProbability(samples: ShortArray) {
        var sumSq = 0.0
        for (s in samples) sumSq += s.toDouble() * s
        val energy = (sumSq / samples.size).toFloat()
        smoothedEnergy = smoothedEnergy * SMOOTHING + energy * (1f - SMOOTHING)
        // Normaliza para [0..1]: 10 000² ≈ energia de sinal de fala forte
        lastVADProbability = (smoothedEnergy / 1e8f).coerceIn(0f, 1f)
    }

    fun release() {
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        Log.d(TAG, "RnnoiseProcessor liberado")
    }
}
