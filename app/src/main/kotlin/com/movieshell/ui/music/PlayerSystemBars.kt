package com.movieshell.ui.music

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * v1.3.2：播放页系统栏跟随整体色——与 1.3.1 阅读页同款做法（单一事实来源）：
 * MusicPlayerScreen 发布当前页的整体色，MainActivity 在 music_player 路由上
 * 用它做状态栏/导航栏底色，离页清掉；图标深浅仍走 MainActivity 现成的
 * luminance 判断。
 *
 * 整体色取法：播放页氛围是模糊封面 + 黑渐变（顶部黑 38%），顶边颜色约等于
 * 封面平均色 × 0.62；无封面时用氛围底色。换歌 → 封面变 → 系统栏跟着变。
 */
object PlayerSystemBars {
    /** 当前播放页整体色；null = 不在播放页内。 */
    var barColor by mutableStateOf<Color?>(null)
        private set

    fun publish(color: Color) {
        barColor = color
    }

    fun clear() {
        barColor = null
    }

    /** 播放页氛围底色（PlayerAtmosphere 的底色，无封面时用它）。 */
    val ATMOSPHERE_BASE = Color(0xFF07070D)

    /** 封面平均色 → 系统栏色：压暗到贴合页面顶边（模糊封面压 38% 黑）。 */
    fun atmosphereBarColor(artAverage: Color?): Color {
        val base = artAverage ?: return ATMOSPHERE_BASE
        return Color(
            red = (base.red * 0.62f).coerceIn(0f, 1f),
            green = (base.green * 0.62f).coerceIn(0f, 1f),
            blue = (base.blue * 0.62f).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }

    /**
     * v1.3.3：无封面时系统栏色——页面顶边实际是品牌渐变首色（0.32 透明度）
     * 铺在氛围底色上，再压一层黑渐变（顶部 0.38，沉浸 0.18）；按同样的
     * 两层叠加算出来，与页面顶边同色。直接回到底色会比页面顶边黑一截，
     * 看着就是“没跟上”。
     */
    fun noArtBarColor(brandTop: Color?, immersive: Boolean): Color {
        val top = brandTop ?: return ATMOSPHERE_BASE
        val overBase = compositeOver(top.copy(alpha = 0.32f), ATMOSPHERE_BASE)
        return compositeOver(Color.Black.copy(alpha = if (immersive) 0.18f else 0.38f), overBase)
    }

    /** src（带透明度）盖在不透明的 dst 上。 */
    fun compositeOver(src: Color, dst: Color): Color {
        val a = src.alpha
        return Color(
            red = (src.red * a + dst.red * (1f - a)).coerceIn(0f, 1f),
            green = (src.green * a + dst.green * (1f - a)).coerceIn(0f, 1f),
            blue = (src.blue * a + dst.blue * (1f - a)).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }

    /** 取 bitmap 的平均色：缩到 1×1（双线性采样即全图平均），不引 Palette。 */
    fun averageColor(bitmap: Bitmap): Color {
        if (bitmap.width <= 0 || bitmap.height <= 0) return ATMOSPHERE_BASE
        val px = Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)
        return Color(px)
    }

    /** 像素数组的平均色（可单测的纯函数；bitmap 版只负责取像素）。 */
    fun averageColorOf(pixels: IntArray): Color {
        if (pixels.isEmpty()) return ATMOSPHERE_BASE
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = pixels.size.toLong()
        return Color(
            red = (r / n).toInt() / 255f,
            green = (g / n).toInt() / 255f,
            blue = (b / n).toInt() / 255f,
            alpha = 1f,
        )
    }
}
