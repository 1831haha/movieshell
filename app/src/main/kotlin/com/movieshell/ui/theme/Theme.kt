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
 * v0.279.0 V4：Material 色板由 V4 token 派生——各屏用 MaterialTheme.colorScheme 的地方
 * 自动跟着换成 V4 平底/三级面/三级字；主题色只替换强调色（primary）。
 * background=bg、surface=s1、surfaceVariant=s2、surfaceContainer*=s1~s3、
 * outline=div2、outlineVariant=div、onSurfaceVariant=t2。
 */
private fun schemeOf(v: V4Colors) = if (v.isDark) darkColorScheme(
    primary = v.accent,
    onPrimary = v.onAccent,
    primaryContainer = lerp(v.accent, v.bg, 0.78f),
    onPrimaryContainer = v.accent,
    secondary = lerp(v.accent, Color.White, 0.3f),
    onSecondary = v.onAccent,
    secondaryContainer = v.s3,
    onSecondaryContainer = v.t1,
    tertiary = v.ok,
    background = v.bg,
    onBackground = v.t1,
    surface = v.s1,
    onSurface = v.t1,
    surfaceVariant = v.s2,
    onSurfaceVariant = v.t2,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = v.bg,
    surfaceContainerLow = v.s1,
    surfaceContainer = v.s1,
    surfaceContainerHigh = v.s2,
    surfaceContainerHighest = v.s3,
    inverseSurface = v.t1,
    inverseOnSurface = v.bg,
    outline = v.div2,
    outlineVariant = v.div,
    error = v.live,
    onError = Color.White,
    scrim = Color.Black,
) else lightColorScheme(
    primary = v.accent,
    onPrimary = v.onAccent,
    primaryContainer = lerp(v.accent, Color.White, 0.86f),
    onPrimaryContainer = lerp(v.accent, Color.Black, 0.35f),
    secondary = lerp(v.accent, Color.White, 0.2f),
    onSecondary = Color.White,
    secondaryContainer = v.s3,
    onSecondaryContainer = v.t1,
    tertiary = v.ok,
    background = v.bg,
    onBackground = v.t1,
    surface = v.s1,
    onSurface = v.t1,
    surfaceVariant = v.s2,
    onSurfaceVariant = v.t2,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = v.s1,
    surfaceContainerLow = v.s1,
    surfaceContainer = v.s1,
    surfaceContainerHigh = v.s2,
    surfaceContainerHighest = v.s3,
    inverseSurface = v.t1,
    inverseOnSurface = v.bg,
    outline = v.div2,
    outlineVariant = v.div,
    error = v.live,
    onError = Color.White,
)

@Composable
fun MovieShellTheme(
    darkTheme: Boolean = true,
    themeColor: Color = V4Accent,
    content: @Composable () -> Unit,
) {
    // v0.87.1：炫彩渐变主题时，品牌渐变面跟随预设色带
    // v0.279.0 V4：单一强调色——没选炫彩预设时“品牌渐变”退化成强调色单色，
    // 顶栏/弹窗不再出现青蓝渐变；选了预设的用户仍按预设色带走（功能保留）。
    // 浅色沿用 v0.237.0 口径：强调色固定，不跟主题色走。
    val brandArgb by ThemeStore.brandGradient.collectAsState()
    val v4 = if (darkTheme) v4Dark(themeColor) else v4Light()
    val brandColors = if (darkTheme) {
        brandArgb?.map { Color(it) } ?: listOf(v4.accent, v4.accent)
    } else {
        listOf(v4.accent, v4.accent)
    }
    CompositionLocalProvider(
        LocalV4 provides v4,
        LocalSoftExtra provides if (darkTheme) SoftExtraDark else SoftExtraLight,
        LocalBrandGradient provides brandColors,
    ) {
        MaterialTheme(
            colorScheme = schemeOf(v4),
            content = content,
        )
    }
}
