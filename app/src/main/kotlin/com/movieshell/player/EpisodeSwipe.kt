package com.movieshell.player

/** v0.294.25：竖屏全屏短剧“上滑下一集/下滑上一集”的纯逻辑（可单测）。 */
object EpisodeSwipe {
    /** 底部区域占比：起点落在下方 40% 才算切集手势 */
    const val ZONE_FRACTION = 0.4f

    /** 竖屏全屏：屏幕竖屏且播放区域本身高大于宽（独立页或详情页全屏都算） */
    fun isPortraitFullscreen(portrait: Boolean, widthPx: Int, heightPx: Int): Boolean =
        portrait && heightPx > widthPx && widthPx > 0

    fun inZone(downY: Float, heightPx: Int): Boolean =
        heightPx > 0 && downY >= heightPx * (1f - ZONE_FRACTION)

    /** 位移 dy（当前 y − 起点 y）：+1 下一集（上滑），-1 上一集（下滑），0 未达阈值 */
    fun direction(dyPx: Float, thresholdPx: Float): Int = when {
        dyPx <= -thresholdPx -> 1
        dyPx >= thresholdPx -> -1
        else -> 0
    }

    /** 起手是否竖向（竖向分量不小于横向） */
    fun isVerticalStart(dx: Float, dy: Float): Boolean = kotlin.math.abs(dy) >= kotlin.math.abs(dx) && dy != 0f

    /** 提示：dir 为意图方向（+1/-1），count 为本线路集数，cur 为当前下标 */
    fun hint(dir: Int, cur: Int, count: Int, armed: Boolean): String {
        val target = cur + dir
        if (target < 0) return "已是第一集"
        if (target >= count) return "已是最后一集"
        val base = (if (dir > 0) "下一集" else "上一集") + " · 第${target + 1}集"
        return if (armed) "松手 $base" else base
    }

    fun target(dir: Int, cur: Int, count: Int): Int? = (cur + dir).takeIf { dir != 0 && it in 0 until count }
}
