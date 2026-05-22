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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun OndasSonorasAgente(isRecording: Boolean, isLoading: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()

    // Base height for bars
    val h1 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = if (isRecording) 40f else if (isLoading) 20f else 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 15f,
        targetValue = if (isRecording) 50f else if (isLoading) 25f else 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = if (isRecording) 60f else if (isLoading) 30f else 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val h4 by infiniteTransition.animateFloat(
        initialValue = 15f,
        targetValue = if (isRecording) 45f else if (isLoading) 25f else 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val h5 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = if (isRecording) 35f else if (isLoading) 20f else 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val color = if (isRecording) Color.Red.copy(alpha = 0.7f) else if (isLoading) Color.Blue.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.5f)

    Row(
        modifier = modifier.height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Bar(height = h1, color = color)
        Bar(height = h2, color = color)
        Bar(height = h3, color = color)
        Bar(height = h4, color = color)
        Bar(height = h5, color = color)
    }
}

@Composable
fun Bar(height: Float, color: Color) {
    Box(
        modifier = Modifier
            .width(6.dp)
            .height(height.dp)
            .clip(CircleShape)
            .background(color)
    )
}
