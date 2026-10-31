package com.movieshell.player

import java.util.Locale
import kotlin.math.abs

/**
 * v0.294.21：长按 3 倍速 + 拖动跳进度的纯计算（无 Android 依赖，单测覆盖）。
 * 横向拖满一屏宽 = 跳 [scrubSpanMs]（片长的一半，限 1~10 分钟），往右前进、往左后退。
 */
object PlayerGestureMath {
    const val BOOST_SPEED = 3.0f
    val SPEED_CHOICES = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    fun scrubSpanMs(durationMs: Long): Long =
        if (durationMs <= 0) 120_000L else (durationMs / 2).coerceIn(60_000L, 600_000L)

    /** 起点 + 拖动距离 → 目标进度（夹在 0..片长）。 */
    fun scrubTarget(startMs: Long, dxPx: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f) return startMs
        val delta = (dxPx / widthPx * scrubSpanMs(durationMs)).toLong()
        val end = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return (startMs + delta).coerceIn(0L, end)
    }

    fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    /** 拖动提示：「» 12:30 / 45:00（+1:20）」。 */
    fun scrubHint(startMs: Long, targetMs: Long, durationMs: Long): String {
        val d = targetMs - startMs
        val arrow = if (d >= 0) "»" else "«"
        val sign = if (d >= 0) "+" else "-"
        val total = if (durationMs > 0) " / ${fmt(durationMs)}" else ""
        return "$arrow ${fmt(targetMs)}$total（$sign${fmt(abs(d))}）"
    }

    /** 速度标签：1.0X / 0.75X / 3.0X。 */
    fun speedLabel(v: Float): String = "${v}X"
}
