package com.movieshell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * v0.27.0 Soft Tech 配色：深空灰底 + 雾蓝主色。
 * 主题换色器只替换 primary，其余 token 固定；onPrimary 按主色明度自动取深/白保证对比度。
 */
private fun darkScheme(primary: Color) = darkColorScheme(
    primary = primary,
    onPrimary = if (primary.luminance() > 0.5f) Color(0xFF06202B) else Color.White,
    secondary = lerp(primary, Color.White, 0.3f),
    background = Color(0xFF101318),
    surface = Color(0xFF181D26),
    surfaceVariant = Color(0xFF202635),
    onBackground = Color(0xFFF2F4F8),
    onSurface = Color(0xFFF2F4F8),
    onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF262D3A),
    outlineVariant = Color(0xFF262D3A),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF2B0A0A),
)

private fun lightScheme(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = if (primary.luminance() > 0.5f) Color(0xFF06202B) else Color.White,
    secondary = lerp(primary, Color.Black, 0.25f),
    background = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF1F7),
    onBackground = Color(0xFF1B1E24),
    onSurface = Color(0xFF1B1E24),
    onSurfaceVariant = Color(0xFF5C6675),
    outline = Color(0xFFE2E8F0),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
)

@Composable
fun MovieShellTheme(
    darkTheme: Boolean = true,
    themeColor: Color = Color(0xFF7DD3FC),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSoftExtra provides if (darkTheme) SoftExtraDark else SoftExtraLight,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(themeColor) else lightScheme(themeColor),
            content = content,
        )
    }
}
