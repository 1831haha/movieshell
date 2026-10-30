package com.movieshell.ui.theme

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v0.292.0 V5 Material You 设计 token 单入口（ui-redesign-v5-m3/design-tokens.md）。
 *
 * 颜色一律走 MaterialTheme.colorScheme（M3 角色），字阶走 MaterialTheme.typography，
 * 形状走 MaterialTheme.shapes；这里只放 M3 色板里没有的语义色扩展、
 * 尺寸（4dp 栅格）与动效时序。上一版 token 层（平底/三级面/1px 分隔线）已整体删除。
 */
object V5 {
    // ---- 间距（4dp 栅格） ----
    /** 页边距 16。 */
    val Gutter: Dp = 16.dp

    // ---- 形状阶（第 3 节） ----
    /** extraSmall：海报角标、分段列表内角。 */
    val CornerXs: Dp = 6.dp
    /** small：chip、meta chip。 */
    val CornerSm: Dp = 8.dp
    /** medium：海报、选集按钮、菜单项、输入框。 */
    val CornerMd: Dp = 12.dp
    /** large：继续观看缩略图、布局菜单、详情海报。 */
    val CornerLg: Dp = 16.dp
    /** largeIncreased：卡片（简介、统计、设置分组、直播播放器 24）。 */
    val CornerLgI: Dp = 20.dp
    val CornerCard: Dp = 24.dp
    /** extraLarge：Hero、搜索栏、侧边面板、中央播放键。 */
    val CornerXl: Dp = 28.dp

    // ---- 高度（第 4 节） ----
    val TopBar: Dp = 64.dp
    val SearchBar: Dp = 56.dp
    val Tabs: Dp = 48.dp
    val FilterStrip: Dp = 48.dp
    val SectionHeader: Dp = 48.dp
    val Chip: Dp = 32.dp
    val Button: Dp = 40.dp
    val ButtonProminent: Dp = 56.dp
    val Field: Dp = 40.dp
    val ListItem: Dp = 56.dp
    val ListItemTwoLine: Dp = 72.dp
    val RowChannel: Dp = 72.dp
    val RowTrack: Dp = 56.dp
    val NavBar: Dp = 80.dp

    /** tab 页滚动内容的底部留白 = NavigationBar 高度（底栏不再浮动，不需要淡出区）。 */
    val DockClearance: Dp = 80.dp

    /** 状态栏下方留白（设置 / 直播 无顶栏，内容从状态栏 + 8dp 开始）。 */
    val StatusGap: Dp = 8.dp
}

/** 动画时序（M3 emphasized decelerate 近似；原 V5Motion 数值不变）。 */
object V5Motion {
    const val Fast = 220
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/** 当前色板是否深色（按 background 明度判，动态取色/海报取色也适用）。 */
val ColorScheme.isDark: Boolean
    get() = background.luminance() < 0.5f

/** 语义扩展色：ok 状态点（design-tokens：#2E8B47 浅 / #7BD88F 深）。 */
val ColorScheme.ok: Color
    get() = if (isDark) Color(0xFF7BD88F) else Color(0xFF2E8B47)

/** 媒体叠层（视频/海报之上，深浅通用）：进度填充 = primary tone 80。 */
val MediaProgressFill = Color(0xFFFFB77A)

/** 媒体叠层：渐变遮罩起点 rgba(0,0,0,.6)。 */
val MediaScrim = Color(0x99000000)

/** 占位图上的细圆环。 */
val ColorScheme.artRing: Color
    get() = onSurface.copy(alpha = 0.10f)

/**
 * 占位图（没图/图挂时）：按种子取一组色板容器色做柔和斜向渐变，跟随主题色，
 * 不用假海报冒充。
 */
fun ColorScheme.artBrush(seed: String): Brush {
    val pairs = listOf(
        primaryContainer to surfaceContainerHigh,
        secondaryContainer to surfaceContainer,
        tertiaryContainer to surfaceContainerHigh,
        surfaceVariant to surfaceContainerLow,
        primaryContainer to tertiaryContainer,
        secondaryContainer to surfaceContainerHighest,
    )
    val (a, b) = pairs[(seed.hashCode() and 0x7fffffff) % pairs.size]
    return Brush.linearGradient(
        0f to a,
        0.85f to b,
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )
}
