package com.movieshell.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v0.27.0 Soft Tech 设计语言 token（单入口）。
 *
 * 深色柔和科技风：深空灰底 + 雾蓝主色，大圆角 + 低对比描边 + 柔和阴影，
 * 去流动光斑/霓虹渐变/重度毛玻璃。主题换色器只替换 primary，其余 token 固定。
 */

/** Material3 色板之外的固定扩展 token。 */
data class SoftExtraColors(
    /** 卡片描边：#262D3A（深色） */
    val border: Color,
    /** 柔和成功色 */
    val success: Color,
    /** 柔和警告色 */
    val warning: Color,
    /** 柔和错误色 */
    val errorSoft: Color,
)

val SoftExtraDark = SoftExtraColors(
    border = Color(0xFF262D3A),
    success = Color(0xFF6EE7B7),
    warning = Color(0xFFFCD34D),
    errorSoft = Color(0xFFFCA5A5),
)

val SoftExtraLight = SoftExtraColors(
    border = Color(0xFFE2E8F0),
    success = Color(0xFF0E9F6E),
    warning = Color(0xFFC27803),
    errorSoft = Color(0xFFDC2626),
)

val LocalSoftExtra = compositionLocalOf { SoftExtraDark }

/** 形状与间距。 */
object SoftDimens {
    val CardCorner = 20.dp
    val ButtonCorner = 16.dp
    val PosterCorner = 12.dp
    val FieldCorner = 14.dp
    val ChipCorner = 50.dp // 胶囊
    val CardSpacing = 12.dp
    val PagePadding = 16.dp
    val BorderWidth = 1.dp
    val TouchTarget = 48.dp
}

/** 字阶。颜色在调用处配（主色 onSurface / 次色 onSurfaceVariant）。 */
object SoftType {
    val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val CardTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
}

/** 动效：统一 300ms ease-out（调用处用 tween(SoftMotion.Fast, easing = SoftMotion.Ease)）。 */
object SoftMotion {
    const val Fast = 300
    val Ease = androidx.compose.animation.core.FastOutSlowInEasing
}
