package com.movieshell.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v0.279.0 V4 界面重设计（Minimal Dense Pro）设计 token 单入口。
 *
 * 口径见设计稿 design-tokens.md：平底 #0B0D12、三级面 s1/s2/s3、1px 分隔线、
 * 三级文字 t1/t2/t3、唯一强调色（默认 #3D9BFF，主题色可换）、ok/live/vip 三种状态色。
 * 内容靠底色深浅与 1px 分隔线区分，不用大圆角卡片、光斑、渐变装饰。
 * 浅色主题给一套同结构的浅色 token（不删浅色），各屏只认 token，不写死颜色。
 */
data class V4Colors(
    val isDark: Boolean,
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val s3: Color,
    val div: Color,
    val div2: Color,
    val t1: Color,
    val t2: Color,
    val t3: Color,
    val accent: Color,
    val onAccent: Color,
    val accentA: Color,
    val ok: Color,
    val live: Color,
    val liveBg: Color,
    val vip: Color,
    val vipBg: Color,
    /** 浮动底栏底色（Compose 1.6 无背景模糊，用半透明底；见 [navSolid]） */
    val navBg: Color,
    /** 无透明度兜底色（低端机/自定义底图时用） */
    val navSolid: Color,
    val navBorder: Color,
    /** 开关关闭态拇指 */
    val thumbOff: Color,
    /** 占位图上的 1px 圆环 */
    val artRing: Color,
    /** 占位图色带（2 段斜向渐变），按标题 hash 取一组 */
    val art: List<Pair<Color, Color>>,
)

private val DarkArt = listOf(
    Color(0xFF3A2A3E) to Color(0xFF1A1620),
    Color(0xFF4A3A22) to Color(0xFF1C1712),
    Color(0xFF1F3550) to Color(0xFF11161F),
    Color(0xFF1E3F3A) to Color(0xFF101917),
    Color(0xFF3A2430) to Color(0xFF18121A),
    Color(0xFF2B2F4F) to Color(0xFF13141F),
    Color(0xFF41342A) to Color(0xFF181411),
    Color(0xFF243B2C) to Color(0xFF111813),
)

private val LightArt = listOf(
    Color(0xFFE9DDEB) to Color(0xFFF5F1F6),
    Color(0xFFEFE4D2) to Color(0xFFF8F4EC),
    Color(0xFFD8E4F2) to Color(0xFFF1F5FA),
    Color(0xFFD6EAE5) to Color(0xFFEFF6F4),
    Color(0xFFEDDBE3) to Color(0xFFF8F0F4),
    Color(0xFFDCDDF0) to Color(0xFFF2F2FA),
    Color(0xFFEDE3DA) to Color(0xFFF8F4F0),
    Color(0xFFD9E8DC) to Color(0xFFF0F6F1),
)

/** V4 默认强调色。 */
val V4Accent = Color(0xFF3D9BFF)

/** 浅色下的强调色：比 #3D9BFF 深一档，白底上对比度够。 */
val V4AccentLight = Color(0xFF1F7AE0)

fun v4Dark(accent: Color = V4Accent): V4Colors = V4Colors(
    isDark = true,
    bg = Color(0xFF0B0D12),
    s1 = Color(0xFF12151C),
    s2 = Color(0xFF181C25),
    s3 = Color(0xFF20252F),
    div = Color(0x0FFFFFFF), // rgba(255,255,255,.06)
    div2 = Color(0x1AFFFFFF), // rgba(255,255,255,.10)
    t1 = Color(0xFFE6E8EE),
    t2 = Color(0xFF8A91A2),
    t3 = Color(0xFF565D6E),
    accent = accent,
    onAccent = if (accent.luminanceApprox() > 0.6f) Color(0xFF0B0D12) else Color.White,
    accentA = accent.copy(alpha = 0.12f),
    ok = Color(0xFF3DD68C),
    live = Color(0xFFFF5A5F),
    liveBg = Color(0x1FFF5A5F),
    vip = Color(0xFFE8B45A),
    vipBg = Color(0x24E8B45A),
    navBg = Color(0xF2161921), // rgba(22,25,33,.95)
    navSolid = Color(0xFF161921),
    navBorder = Color(0x12FFFFFF), // rgba(255,255,255,.07)
    thumbOff = Color(0xFF8A91A2),
    artRing = Color(0x1FFFFFFF),
    art = DarkArt,
)

fun v4Light(accent: Color = V4AccentLight): V4Colors = V4Colors(
    isDark = false,
    bg = Color(0xFFF6F7F9),
    s1 = Color(0xFFFFFFFF),
    s2 = Color(0xFFEEF0F3),
    s3 = Color(0xFFE3E6EB),
    div = Color(0x12000000),
    div2 = Color(0x1F000000),
    t1 = Color(0xFF15181E),
    t2 = Color(0xFF5B6272),
    t3 = Color(0xFF8E95A3),
    accent = accent,
    onAccent = Color.White,
    accentA = accent.copy(alpha = 0.10f),
    ok = Color(0xFF14A866),
    live = Color(0xFFE5484D),
    liveBg = Color(0x1AE5484D),
    vip = Color(0xFFB7801F),
    vipBg = Color(0x1FB7801F),
    navBg = Color(0xF2FFFFFF),
    navSolid = Color(0xFFFFFFFF),
    navBorder = Color(0x14000000),
    thumbOff = Color(0xFFFFFFFF),
    artRing = Color(0x14000000),
    art = LightArt,
)

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

val LocalV4 = staticCompositionLocalOf { v4Dark() }

/** 取当前 V4 token：`V4.c.bg`。 */
object V4 {
    val c: V4Colors
        @Composable @ReadOnlyComposable get() = LocalV4.current

    // ---- 圆角 ----
    val RTag: Dp = 3.dp
    val RSm: Dp = 4.dp
    val RPoster: Dp = 6.dp
    val RField: Dp = 8.dp
    val RTile: Dp = 10.dp
    val RWell: Dp = 12.dp

    // ---- 间距 ----
    val Gutter: Dp = 12.dp

    // ---- 高度 ----
    val TopBar: Dp = 42.dp
    val TabRow: Dp = 36.dp
    val FilterStrip: Dp = 34.dp
    val SectionHeader: Dp = 32.dp
    val RowChannel: Dp = 40.dp
    val RowTrack: Dp = 42.dp
    val RowSetting: Dp = 44.dp
    val Field: Dp = 34.dp

    // ---- 底栏（NAV B） ----
    val NavHeight: Dp = 48.dp
    val NavBottom: Dp = 14.dp
    val NavWidth: Dp = 334.dp
    val NavItem: Dp = 44.dp
    val NavItemActive: Dp = 84.dp
    /** 每个 tab 页滚动内容的底部留白：14（底距）+ 48（底栏）+ 16（间隙） */
    val DockClearance: Dp = 78.dp
    /** 底栏后面的内容淡出区高度 */
    val DockFade: Dp = 100.dp

    /** 等宽数字字体（序号、时长、版本、计数）。系统 monospace，开 tnum。 */
    val Mono: FontFamily = FontFamily.Monospace
}

/** V4 字阶（颜色在调用处配）。 */
object V4Type {
    val ScreenTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val Tab = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal)
    val TabOn = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    val RowTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal)
    val Section = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    val PosterTitle = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
    val Meta = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val NavLabel = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    val Stat = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
    val MonoMeta = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
    val MonoSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
    val Tag = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp)
}

/** 占位图：按种子取一组柔和斜向渐变（不用真实海报冒充）。 */
fun V4Colors.artBrush(seed: String): Brush {
    val (a, b) = art[(seed.hashCode() and 0x7fffffff) % art.size]
    return Brush.linearGradient(
        0f to a,
        0.7f to b,
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )
}

/** 动画时序（原 SoftTech.SoftMotion，286 随 SoftTech 删除迁入 V4，数值不变）。 */
object SoftMotion {
    const val Fast = 220
    val Ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
