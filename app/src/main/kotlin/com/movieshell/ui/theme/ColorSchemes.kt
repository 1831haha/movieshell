package com.movieshell.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot

/**
 * v0.292.0 V5 Material You：M3 色板。
 *
 * - 种子 #D9822B（暖琥珀，取自设计稿首屏海报「长河落日」）+ TonalSpot，
 *   色值逐个照抄 ui-redesign-v5-m3/design-tokens.md 第 1 节（material-color-utilities 生成）；
 * - 其它种子（主题色预设 / 海报取色）用同一套算法现算（materialkolor 的 MCU 移植，纯 Kotlin，无 Compose 依赖）。
 */
val SeedAmber = Color(0xFFD9822B)

val XiaYingLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF88511D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF6B3B05),
    secondary = Color(0xFF745943),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = Color(0xFF5A422E),
    tertiary = Color(0xFF5B6237),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E7B1),
    onTertiaryContainer = Color(0xFF444A22),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A14),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A14),
    surfaceVariant = Color(0xFFF3DFD1),
    onSurfaceVariant = Color(0xFF51443B),
    surfaceTint = Color(0xFF88511D),
    surfaceDim = Color(0xFFE6D7CD),
    surfaceBright = Color(0xFFFFF8F5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1E8),
    surfaceContainer = Color(0xFFFBEBE1),
    surfaceContainerHigh = Color(0xFFF5E5DB),
    surfaceContainerHighest = Color(0xFFEFE0D6),
    outline = Color(0xFF847469),
    outlineVariant = Color(0xFFD6C3B6),
    inverseSurface = Color(0xFF382F28),
    inverseOnSurface = Color(0xFFFEEEE4),
    inversePrimary = Color(0xFFFFB77A),
    scrim = Color(0xFF000000),
)

val XiaYingDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB77A),
    onPrimary = Color(0xFF4C2700),
    primaryContainer = Color(0xFF6B3B05),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE3C0A5),
    onSecondary = Color(0xFF412C19),
    secondaryContainer = Color(0xFF5A422E),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFC4CB97),
    onTertiary = Color(0xFF2D330D),
    tertiaryContainer = Color(0xFF444A22),
    onTertiaryContainer = Color(0xFFE0E7B1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF19120C),
    onBackground = Color(0xFFEFE0D6),
    surface = Color(0xFF19120C),
    onSurface = Color(0xFFEFE0D6),
    surfaceVariant = Color(0xFF51443B),
    onSurfaceVariant = Color(0xFFD6C3B6),
    surfaceTint = Color(0xFFFFB77A),
    surfaceDim = Color(0xFF19120C),
    surfaceBright = Color(0xFF413731),
    surfaceContainerLowest = Color(0xFF140D08),
    surfaceContainerLow = Color(0xFF221A14),
    surfaceContainer = Color(0xFF261E18),
    surfaceContainerHigh = Color(0xFF312822),
    surfaceContainerHighest = Color(0xFF3C332C),
    outline = Color(0xFF9E8E82),
    outlineVariant = Color(0xFF51443B),
    inverseSurface = Color(0xFFEFE0D6),
    inverseOnSurface = Color(0xFF382F28),
    inversePrimary = Color(0xFF88511D),
    scrim = Color(0xFF000000),
)

private val schemeCache = HashMap<Long, ColorScheme>()

/**
 * 种子色 → M3 TonalSpot 色板。默认种子直接返回设计稿 token（与稿子逐值一致）；
 * 其它种子用 MCU 现算并缓存（算一次几毫秒，主题切换/海报换色才会走到）。
 */
fun seedColorScheme(seedArgb: Int, dark: Boolean): ColorScheme {
    val seed = seedArgb or 0xFF000000.toInt()
    if (seed == SeedAmber.toArgbInt()) return if (dark) XiaYingDarkScheme else XiaYingLightScheme
    val key = (seed.toLong() shl 1) or (if (dark) 1L else 0L)
    synchronized(schemeCache) {
        schemeCache[key]?.let { return it }
    }
    val scheme = runCatching { computeTonalSpot(seed, dark) }
        .getOrElse { if (dark) XiaYingDarkScheme else XiaYingLightScheme }
    synchronized(schemeCache) {
        if (schemeCache.size > 32) schemeCache.clear()
        schemeCache[key] = scheme
    }
    return scheme
}

/** MCU TonalSpot（与 Android 12+ 系统取色同一算法，contrast 0）。 */
fun computeTonalSpot(seedArgb: Int, dark: Boolean): ColorScheme {
    val s = SchemeTonalSpot(Hct.fromInt(seedArgb), dark, 0.0)
    val m = MaterialDynamicColors()
    fun c(dc: com.materialkolor.dynamiccolor.DynamicColor) = Color(dc.getArgb(s))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(m.primary()),
        onPrimary = c(m.onPrimary()),
        primaryContainer = c(m.primaryContainer()),
        onPrimaryContainer = c(m.onPrimaryContainer()),
        inversePrimary = c(m.inversePrimary()),
        secondary = c(m.secondary()),
        onSecondary = c(m.onSecondary()),
        secondaryContainer = c(m.secondaryContainer()),
        onSecondaryContainer = c(m.onSecondaryContainer()),
        tertiary = c(m.tertiary()),
        onTertiary = c(m.onTertiary()),
        tertiaryContainer = c(m.tertiaryContainer()),
        onTertiaryContainer = c(m.onTertiaryContainer()),
        background = c(m.background()),
        onBackground = c(m.onBackground()),
        surface = c(m.surface()),
        onSurface = c(m.onSurface()),
        surfaceVariant = c(m.surfaceVariant()),
        onSurfaceVariant = c(m.onSurfaceVariant()),
        surfaceTint = c(m.surfaceTint()),
        inverseSurface = c(m.inverseSurface()),
        inverseOnSurface = c(m.inverseOnSurface()),
        error = c(m.error()),
        onError = c(m.onError()),
        errorContainer = c(m.errorContainer()),
        onErrorContainer = c(m.onErrorContainer()),
        outline = c(m.outline()),
        outlineVariant = c(m.outlineVariant()),
        scrim = c(m.scrim()),
        surfaceBright = c(m.surfaceBright()),
        surfaceDim = c(m.surfaceDim()),
        surfaceContainer = c(m.surfaceContainer()),
        surfaceContainerHigh = c(m.surfaceContainerHigh()),
        surfaceContainerHighest = c(m.surfaceContainerHighest()),
        surfaceContainerLow = c(m.surfaceContainerLow()),
        surfaceContainerLowest = c(m.surfaceContainerLowest()),
    )
}

internal fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt()
    val r = (red * 255f + 0.5f).toInt()
    val g = (green * 255f + 0.5f).toInt()
    val b = (blue * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
