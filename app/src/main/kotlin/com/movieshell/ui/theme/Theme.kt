package com.movieshell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.movieshell.data.repo.ThemeStore

/**
 * v0.218.0（S28）：品牌渐变色带从顶栏组件文件搬到主题层——原来主题层
 * 反向依赖组件层，动顶栏文件会动到全仓主题；值与之前一致。
 */
val BrandGradientColors = listOf(
    Color(0xFF4DD0E1), // 青
    Color(0xFF22D3EE), // 亮青
    Color(0xFF2086FF), // 蓝
)

/**
 * v0.87.1：品牌渐变 CompositionLocal。
 * 单色主题下取默认 [BrandGradientColors]（行为与之前一致）；
 * 炫彩渐变主题下取所选预设的色带，顶栏品牌线/弹窗描边/跑马灯等跟随换色。
 */
val LocalBrandGradient = compositionLocalOf { BrandGradientColors }

/**
 * v0.33.0 影院黑配色：底 #050508 + 青主色（风格统一后取代紫主色）。
 * 主题换色器只替换 primary，其余 token 固定；onPrimary 按主色明度自动取深/白保证对比度。
 */
private fun darkScheme(primary: Color) = darkColorScheme(
    primary = primary,
    onPrimary = if (primary.luminance() > 0.5f) Color(0xFF1A1408) else Color.White,
    // v0.274.0（T41）：primaryContainer 按 primary 派生——原来没定义，
    // 走 Material 默认紫，搜索左栏选中、日志球、书详情弹窗都跟着发紫。
    primaryContainer = lerp(primary, Color(0xFF050508), 0.75f),
    onPrimaryContainer = primary,
    secondary = lerp(primary, Color.White, 0.3f),
    background = Color(0xFF050508),
    surface = Color(0xFF14141B),
    surfaceVariant = Color(0xFF1E1E28),
    onBackground = Color(0xFFF2F4F8),
    onSurface = Color(0xFFF2F4F8),
    onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF26262F),
    outlineVariant = Color(0xFF26262F),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF2B0A0A),
)

/**
 * v0.237.0 浅色高密度改版：浅色主题按参考图固定——纯白底、近黑字、
 * 参考蓝主色，不跟主题色选择走（主题色与炫彩预设只作用于深色主题）；
 * 这样不管之前选过什么主题色，装上就是参考图的浅色样子。
 */
private fun lightScheme() = lightColorScheme(
    primary = Color(0xFF1E88E5),
    onPrimary = Color.White,
    // v0.274.0（T41）：同深色——浅色下 primaryContainer 按参考蓝派生浅色底，
    // 不再走 Material 默认紫。
    primaryContainer = lerp(Color(0xFF1E88E5), Color.White, 0.85f),
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF42A5F5),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F3F4),
    onBackground = Color(0xFF202124),
    onSurface = Color(0xFF202124),
    onSurfaceVariant = Color(0xFF5F6364),
    outline = Color(0xFFE8EAED),
    outlineVariant = Color(0xFFE8EAED),
    error = Color(0xFFD32F2F),
)

@Composable
fun MovieShellTheme(
    darkTheme: Boolean = true,
    themeColor: Color = Color(0xFF4DD0E1),
    content: @Composable () -> Unit,
) {
    // v0.87.1：炫彩渐变主题时，品牌渐变面跟随预设色带
    // v0.237.0：浅色下品牌渐变固定参考蓝单色系，不跟预设走
    val brandArgb by ThemeStore.brandGradient.collectAsState()
    val brandColors = if (darkTheme) {
        brandArgb?.map { Color(it) } ?: BrandGradientColors
    } else {
        listOf(Color(0xFF1E88E5), Color(0xFF42A5F5))
    }
    CompositionLocalProvider(
        LocalSoftExtra provides if (darkTheme) SoftExtraDark else SoftExtraLight,
        LocalBrandGradient provides brandColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(themeColor) else lightScheme(),
            content = content,
        )
    }
}
