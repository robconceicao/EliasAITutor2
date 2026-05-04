package com.roberto.eliasaitutor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

@Composable
fun RadarChart(
    labels: List<String>,
    values: List<Float>,   // 0f..100f
    modifier: Modifier = Modifier,
    accentColor: Color  = Color(0xFF4f8ef7),
    gridColor: Color    = Color(0xFF252a35),
    labelColor: Color   = Color(0xFFe8eaf0),
) {
    require(labels.size == values.size)
    val n = labels.size

    Canvas(modifier = modifier) {
        val cx = size.width  / 2f
        val cy = size.height / 2f
        val r  = minOf(cx, cy) * 0.70f

        // Grid rings
        for (ring in 1..4) {
            val ringR = r * ring / 4f
            val path = Path()
            for (i in 0 until n) {
                val angle = (2 * PI / n * i - PI / 2).toFloat()
                val px    = cx + ringR * cos(angle)
                val py    = cy + ringR * sin(angle)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, gridColor, style = Stroke(width = 1f))
        }
        // Axes
        for (i in 0 until n) {
            val angle = (2 * PI / n * i - PI / 2).toFloat()
            drawLine(gridColor, Offset(cx, cy),
                Offset(cx + r * cos(angle), cy + r * sin(angle)), strokeWidth = 1f)
        }
        // Data polygon
        val dataPath = Path()
        for (i in 0 until n) {
            val angle = (2 * PI / n * i - PI / 2).toFloat()
            val v     = values[i].coerceIn(0f, 100f) / 100f
            val px    = cx + r * v * cos(angle)
            val py    = cy + r * v * sin(angle)
            if (i == 0) dataPath.moveTo(px, py) else dataPath.lineTo(px, py)
        }
        dataPath.close()
        drawPath(dataPath, accentColor.copy(alpha = 0.20f))
        drawPath(dataPath, accentColor, style = Stroke(width = 2.5f))

        // Data dots
        for (i in 0 until n) {
            val angle = (2 * PI / n * i - PI / 2).toFloat()
            val v     = values[i].coerceIn(0f, 100f) / 100f
            drawCircle(accentColor, radius = 5f,
                center = Offset(cx + r * v * cos(angle), cy + r * v * sin(angle)))
        }
        // Labels
        val paint = android.graphics.Paint().apply {
            color     = labelColor.hashCode()
            textSize  = 13.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        for (i in 0 until n) {
            val angle = (2 * PI / n * i - PI / 2).toFloat()
            val lx    = cx + (r + 24f) * cos(angle)
            val ly    = cy + (r + 24f) * sin(angle) + paint.textSize / 3
            drawContext.canvas.nativeCanvas.drawText(labels[i], lx, ly, paint)
        }
    }
}
