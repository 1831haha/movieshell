package com.movieshell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFF9DE0C4),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF131722),
    surfaceVariant = Color(0xFF1C2230),
    onBackground = Color(0xFFE8EBF3),
    onSurface = Color(0xFFE8EBF3),
)

@Composable
fun MovieShellTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
