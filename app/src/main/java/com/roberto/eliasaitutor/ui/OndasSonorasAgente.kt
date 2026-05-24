package com.roberto.eliasaitutor.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun OndasSonorasAgente(
    isRecording: Boolean,
    isLoading: Boolean,
    isIaSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")

    // Dynamic heights based on state
    val h1 by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = if (isRecording) 38f else if (isIaSpeaking) 42f else if (isLoading) 16f else 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = if (isRecording) 48f else if (isIaSpeaking) 54f else if (isLoading) 20f else 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 16f,
        targetValue = if (isRecording) 58f else if (isIaSpeaking) 64f else if (isLoading) 24f else 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h3"
    )
    val h4 by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = if (isRecording) 68f else if (isIaSpeaking) 76f else if (isLoading) 28f else 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h4"
    )
    val h5 by infiniteTransition.animateFloat(
        initialValue = 16f,
        targetValue = if (isRecording) 54f else if (isIaSpeaking) 60f else if (isLoading) 24f else 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h5"
    )
    val h6 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = if (isRecording) 44f else if (isIaSpeaking) 48f else if (isLoading) 20f else 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h6"
    )
    val h7 by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = if (isRecording) 34f else if (isIaSpeaking) 36f else if (isLoading) 16f else 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h7"
    )

    // Curated gradients for states to look extremely modern and premium
    val brush = when {
        isRecording -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF5252), // Bright Red
                Color(0xFFFF7A00), // Vibrant Orange
                Color(0xFFFFD600)  // Gold Accent
            )
        )
        isIaSpeaking -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF00E5FF), // Neon Cyan
                Color(0xFF2979FF), // Deep Blue
                Color(0xFF00E676)  // Bright Emerald
            )
        )
        isLoading -> Brush.verticalGradient(
            colors = listOf(
                Color(0x802979FF), // Translucent Blue
                Color(0x8000E5FF)  // Translucent Cyan
            )
        )
        else -> Brush.verticalGradient(
            colors = listOf(
                Color(0x40FFFFFF),
                Color(0x20FFFFFF)
            )
        )
    }

    Row(
        modifier = modifier
            .height(80.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Bar(height = h1, brush = brush)
        Bar(height = h2, brush = brush)
        Bar(height = h3, brush = brush)
        Bar(height = h4, brush = brush)
        Bar(height = h5, brush = brush)
        Bar(height = h6, brush = brush)
        Bar(height = h7, brush = brush)
    }
}

@Composable
fun Bar(height: Float, brush: Brush) {
    Box(
        modifier = Modifier
            .width(6.dp)
            .height(height.dp)
            .clip(CircleShape)
            .background(brush)
    )
}
