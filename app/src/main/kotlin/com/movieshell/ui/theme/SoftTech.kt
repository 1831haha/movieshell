package com.movieshell.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v0.33.0 影院黑设计语言 token（单入口，按设计稿对齐）。
 * v0.279.0：数值已对齐 V4（见 V4.kt），新代码优先直接用 V4 token。
 *
 * 底 #050508 + 紫主色，流动渐变光斑 + 毛玻璃卡片 + 微光描边，
 * 影院感。主题换色器只替换 primary，其余 token 固定。
 * 低版本无 backdrop-filter，毛玻璃用半透明色降级。
 */

/** Material3 色板之外的固定扩展 token。 */
data class SoftExtraColors(
    /** 毛玻璃卡片底：rgba(25,25,35,0.22)（深色） */
    val glass: Color,
    /** 卡片描边：rgba(210,210,255,0.12)（深色） */
    val border: Color,
    /** 柔和成功色 */
    val success: Color,
    /** 柔和警告色 */
    val warning: Color,
    /** 柔和错误色 */
    val errorSoft: Color,
)

// v0.279.0 V4：扩展 token 对齐 V4——“玻璃”改实色 s1，描边改 1px 分隔线 div，
// 状态色取 ok/vip/live；字段名不动，老调用点自动换成 V4 样子。
val SoftExtraDark = SoftExtraColors(
    glass = Color(0xFF12151C),
    border = Color(0x0FFFFFFF),
    success = Color(0xFF3DD68C),
    warning = Color(0xFFE8B45A),
    errorSoft = Color(0xFFFF7A7E),
)

val SoftExtraLight = SoftExtraColors(
    glass = Color(0xFFFFFFFF),
    border = Color(0x12000000),
    success = Color(0xFF14A866),
    warning = Color(0xFFB7801F),
    errorSoft = Color(0xFFE5484D),
)

val LocalSoftExtra = compositionLocalOf { SoftExtraDark }

/** 形状与间距（v0.279.0 V4：圆角收到 6–8dp，页边距 12dp）。 */
object SoftDimens {
    val CardCorner = 8.dp
    val ButtonCorner = 8.dp
    val PosterCorner = 6.dp
    val FieldCorner = 8.dp
    val ChipCorner = 6.dp
    val CardSpacing = 8.dp
    val PagePadding = 12.dp
    val BorderWidth = 1.dp
    val TouchTarget = 48.dp
}

/** 字阶（v0.279.0 V4：正文 12.5–13sp，标题 15sp）。颜色在调用处配。 */
object SoftType {
    val Title = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val CardTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val Caption = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)
}

/** 动效：统一 220ms 强调减速（V4 口径；调用处用 tween(SoftMotion.Fast, easing = SoftMotion.Ease)）。 */
object SoftMotion {
    const val Fast = 220
    val Ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
