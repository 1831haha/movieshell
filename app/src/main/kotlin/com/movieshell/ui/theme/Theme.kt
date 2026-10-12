package com.movieshell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

/** 浅色：米白底，primary 调深保证浅底上的对比度。 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF4C63D2),
    onPrimary = Color.White,
    secondary = Color(0xFF2E7D5B),
    background = Color(0xFFF7F2EC),
    surface = Color(0xFFFFFBF6),
    surfaceVariant = Color(0xFFEDE5D8),
    onBackground = Color(0xFF1B1B1F),
    onSurface = Color(0xFF1B1B1F),
    onSurfaceVariant = Color(0xFF5A564E),
)

@Composable
fun MovieShellTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
