package com.roberto.eliasaitutor.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Design tokens — Fases 4–5 visual polish.
 * Dark “studio” palette for Programa / Echo / Immersion / Progress.
 * Chat keeps its light theme for conversation readability.
 */
object EliasTokens {
    val Bg = Color(0xFF0B0D12)
    val Surface = Color(0xFF141822)
    val SurfaceElevated = Color(0xFF1A1F2C)
    val Border = Color(0xFF2A3142)
    val Accent = Color(0xFF4F8EF7)
    val AccentSoft = Color(0xFF2B4A8A)
    val Teal = Color(0xFF2DD4BF)
    val Purple = Color(0xFFA78BFA)
    val Gold = Color(0xFFF7C94F)
    val Green = Color(0xFF34D399)
    val Red = Color(0xFFF87171)
    val Orange = Color(0xFFFB923C)
    val Muted = Color(0xFF8B93A7)
    val TextMain = Color(0xFFF1F5F9)
    val TextDim = Color(0xFFCBD5E1)

    val HeroBrush = Brush.verticalGradient(
        listOf(Color(0xFF152038), Color(0xFF0B0D12))
    )
    val AccentBrush = Brush.horizontalGradient(
        listOf(Accent, Teal)
    )
    val IpaBrush = Brush.horizontalGradient(
        listOf(Purple.copy(alpha = 0.35f), Accent.copy(alpha = 0.25f))
    )
    val ReviewBrush = Brush.verticalGradient(
        listOf(Color(0xFF3D2410), Color(0xFF1A1208))
    )
}
