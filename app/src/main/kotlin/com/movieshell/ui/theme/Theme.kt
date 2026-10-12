package com.movieshell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * v0.12.0：主题色驱动的配色。primary 取用户所选主题色，深浅模式下自动调亮度保证对比度。
 */
private fun darkScheme(primary: Color) = darkColorScheme(
    primary = primary,
    onPrimary = Color.White,
    secondary = lerp(primary, Color.White, 0.3f),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF131722),
    surfaceVariant = Color(0xFF1C2230),
    onBackground = Color(0xFFE8EBF3),
    onSurface = Color(0xFFE8EBF3),
    onSurfaceVariant = Color(0xFF9AA3B2),
)

private fun lightScheme(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    secondary = lerp(primary, Color.Black, 0.25f),
    background = Color(0xFFF7F2EC),
    surface = Color(0xFFFFFBF6),
    surfaceVariant = Color(0xFFEDE5D8),
    onBackground = Color(0xFF1B1B1F),
    onSurface = Color(0xFF1B1B1F),
    onSurfaceVariant = Color(0xFF5A564E),
)

@Composable
fun MovieShellTheme(
    darkTheme: Boolean = true,
    themeColor: Color = Color(0xFF2B7DE9),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme(themeColor) else lightScheme(themeColor),
        content = content,
    )
}

/**
 * 影图式渐变底：主题色 → 深色的垂直渐变。给首页背景用。
 */
fun themeGradientColors(themeColor: Color, darkTheme: Boolean): List<Color> {
    return if (darkTheme) {
        listOf(
            lerp(themeColor, Color.Black, 0.55f),
            Color(0xFF0B0E14),
        )
    } else {
        listOf(
            lerp(themeColor, Color.White, 0.55f),
            Color(0xFFF7F2EC),
        )
    }
}
