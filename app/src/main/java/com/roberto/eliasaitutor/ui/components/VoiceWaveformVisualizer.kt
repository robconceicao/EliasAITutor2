package com.roberto.eliasaitutor.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceWaveformVisualizer(
    rms: Float,
    isRecording: Boolean,
    isIaSpeaking: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val numBars = 48
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    // Slow breathing animation (organic feeling)
    val breathingAnim by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )

    // Fast rotation animation to make it look active
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isIaSpeaking) 6000 else 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Dynamic colors based on active states
    val gradientColors = when {
        isRecording -> listOf(
            Color(0xFFFF5252), // Bright Neon Red
            Color(0xFFFF7A00), // Vibrant Orange
            Color(0xFFFFD600)  // Golden Yellow
        )
        isIaSpeaking -> listOf(
            Color(0xFF00E5FF), // Electric Neon Cyan
            Color(0xFF2979FF), // Royal Blue
            Color(0xFF00E676)  // Bright Emerald Green
        )
        isLoading -> listOf(
            Color(0xFFa855f7), // Purple
            Color(0xFF4f8ef7)  // Blue
        )
        else -> listOf(
            Color(0xFF7a8099).copy(alpha = 0.4f),
            Color(0xFF252a35).copy(alpha = 0.2f)
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = size.height * 0.25f // inner circle radius

        val brush = Brush.sweepGradient(
            colors = gradientColors,
            center = center
        )

        for (i in 0 until numBars) {
            // Apply rotation angle offset
            val angleDegrees = (i * (360f / numBars)) + rotationAngle
            val angleRad = Math.toRadians(angleDegrees.toDouble())

            // Amplitude calculation
            // If speaking, use RMS, else use slight breathing
            val amplitude = when {
                isRecording || isIaSpeaking -> {
                    // Normalize RMS (rms is typically 0 to 8000, let's map it smoothly)
                    val normalizedRms = (rms / 2500f).coerceIn(0f, 1f)
                    // Add some organic wave pattern based on bar index
                    val indexFactor = sin(i * 0.5 + rotationAngle / 20f).toFloat() * 0.2f + 0.8f
                    normalizedRms * 60f * indexFactor
                }
                isLoading -> {
                    // Wave pattern for loading
                    (sin(i * 0.3 + rotationAngle / 10f).toFloat() + 1f) * 12f
                }
                else -> {
                    // Idle breathing
                    (sin(i * 0.2 + rotationAngle / 30f).toFloat() + 1f) * 4f * breathingAnim
                }
            }

            val startRadius = baseRadius
            val endRadius = baseRadius + 12f + amplitude

            val startX = (center.x + startRadius * cos(angleRad)).toFloat()
            val startY = (center.y + startRadius * sin(angleRad)).toFloat()

            val endX = (center.x + endRadius * cos(angleRad)).toFloat()
            val endY = (center.y + endRadius * sin(angleRad)).toFloat()

            drawLine(
                brush = brush,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
        }
    }
}
