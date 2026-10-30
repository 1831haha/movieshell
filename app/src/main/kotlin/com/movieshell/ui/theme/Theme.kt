package com.movieshell.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * v0.292.0 V5：M3 字阶（design-tokens.md 第 2 节）。
 * 页面顶栏统一 titleLarge 22/28、500、−0.2 字距；内容标题（详情片名）headlineMedium 28/34 粗体。
 */
val XiaYingType: Typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )
}

/** v0.292.0 V5：M3 形状阶（design-tokens.md 第 3 节）。 */
val XiaYingShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * v0.292.0 V5 Material You 主题入口。
 *
 * 色板来源（[ThemeStore.source]）：
 * - seed（默认）：[themeColor] 作种子按 TonalSpot 生成；默认种子 #D9822B 直接用设计稿 token；
 *   炫彩渐变预设用其锚点色作种子，品牌渐变色带（[LocalBrandGradient]）照旧跟预设走；
 * - poster：首页 Hero 当前海报取色（[ThemeStore.posterSeed]），没取到前按 [themeColor] 兜底；
 * - dynamic：Android 12+ 系统壁纸取色（dynamicLight/DarkColorScheme），只有用户在设置里选了才用。
 * 色板变化（海报切换）400ms 渐变过渡。
 */
@Composable
fun MovieShellTheme(
    darkTheme: Boolean = true,
    themeColor: Color = SeedAmber,
    content: @Composable () -> Unit,
) {
    val brandArgb by ThemeStore.brandGradient.collectAsState()
    val source by ThemeStore.source.collectAsState()
    val posterSeed by ThemeStore.posterSeed.collectAsState()
    val ctx = LocalContext.current
    val seedArgb = themeColor.toArgbInt()
    val target: ColorScheme = remember(darkTheme, seedArgb, source, posterSeed) {
        when {
            source == ThemeStore.SOURCE_DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            source == ThemeStore.SOURCE_POSTER && posterSeed != null ->
                seedColorScheme(posterSeed!!, darkTheme)
            else -> seedColorScheme(seedArgb, darkTheme)
        }
    }
    val scheme = animateScheme(target)
    val brandColors = brandArgb?.map { Color(it) } ?: listOf(scheme.primary, scheme.primary)
    CompositionLocalProvider(
        LocalBrandGradient provides brandColors,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = XiaYingType,
            shapes = XiaYingShapes,
            content = content,
        )
    }
}

/** 色板逐角色 400ms 渐变（海报取色换片时不闪）。 */
@Composable
private fun animateScheme(t: ColorScheme): ColorScheme {
    @Composable
    fun a(c: Color, label: String): Color =
        animateColorAsState(c, tween(400), label = label).value
    return t.copy(
        primary = a(t.primary, "primary"),
        onPrimary = a(t.onPrimary, "onPrimary"),
        primaryContainer = a(t.primaryContainer, "primaryContainer"),
        onPrimaryContainer = a(t.onPrimaryContainer, "onPrimaryContainer"),
        inversePrimary = a(t.inversePrimary, "inversePrimary"),
        secondary = a(t.secondary, "secondary"),
        onSecondary = a(t.onSecondary, "onSecondary"),
        secondaryContainer = a(t.secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = a(t.onSecondaryContainer, "onSecondaryContainer"),
        tertiary = a(t.tertiary, "tertiary"),
        onTertiary = a(t.onTertiary, "onTertiary"),
        tertiaryContainer = a(t.tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = a(t.onTertiaryContainer, "onTertiaryContainer"),
        background = a(t.background, "background"),
        onBackground = a(t.onBackground, "onBackground"),
        surface = a(t.surface, "surface"),
        onSurface = a(t.onSurface, "onSurface"),
        surfaceVariant = a(t.surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = a(t.onSurfaceVariant, "onSurfaceVariant"),
        surfaceTint = a(t.surfaceTint, "surfaceTint"),
        inverseSurface = a(t.inverseSurface, "inverseSurface"),
        inverseOnSurface = a(t.inverseOnSurface, "inverseOnSurface"),
        outline = a(t.outline, "outline"),
        outlineVariant = a(t.outlineVariant, "outlineVariant"),
        surfaceBright = a(t.surfaceBright, "surfaceBright"),
        surfaceDim = a(t.surfaceDim, "surfaceDim"),
        surfaceContainer = a(t.surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh = a(t.surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest = a(t.surfaceContainerHighest, "surfaceContainerHighest"),
        surfaceContainerLow = a(t.surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainerLowest = a(t.surfaceContainerLowest, "surfaceContainerLowest"),
    )
}
