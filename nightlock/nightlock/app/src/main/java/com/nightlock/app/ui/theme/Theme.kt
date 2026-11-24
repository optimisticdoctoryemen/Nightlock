package com.nightlock.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NightBackground = Color(0xFF07090F)
val NightSurface = Color(0xFF111624)
val NightSurfaceVariant = Color(0xFF1A2236)
val NightIndigo = Color(0xFF818CF8)
val NightAmber = Color(0xFFF59E0B)
val NightGreen = Color(0xFF34D399)
val NightRed = Color(0xFFF87171)
val NightTextPrimary = Color(0xFFF1F5F9)
val NightTextMuted = Color(0xFF94A3B8)

private val NightColorScheme = darkColorScheme(
    primary = NightIndigo,
    onPrimary = Color(0xFF0B0E18),
    secondary = NightAmber,
    onSecondary = Color(0xFF140D00),
    background = NightBackground,
    onBackground = NightTextPrimary,
    surface = NightSurface,
    onSurface = NightTextPrimary,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightTextMuted,
    error = NightRed,
    onError = Color(0xFF1E0505)
)

@Composable
fun NightLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightColorScheme,
        content = content
    )
}
