package com.movieshell.player.danmaku

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * v0.54：弹幕渲染覆盖层（Compose Canvas 自绘）。
 * - 滚动弹幕：从右向左，约 6 秒过屏；按轨道排布防重叠；
 * - 顶部/底部固定：停留 4 秒。
 * 只渲染当前时间窗口内的弹幕（前后各留余量），列表再长也不卡。
 */
@Composable
fun DanmakuOverlay(
    danmakus: List<Danmaku>,
    positionMs: Long,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled || danmakus.isEmpty()) return

    // 帧时钟：约 30fps 重绘
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            frameMs = System.currentTimeMillis()
            delay(33)
        }
    }

    // 按轨道分配（记住分配结果，position 跳变时重算）
    val layout = remember(danmakus) { DanmakuLayout(danmakus) }
    // v0.55：Paint 复用，不每帧 new（之前每帧 new 两个 Paint，GC 压力大）
    val paint = remember {
        android.graphics.Paint().apply {
            textSize = 42f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val strokePaint = remember {
        android.graphics.Paint().apply {
            textSize = 42f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f
            color = android.graphics.Color.BLACK
            alpha = 180
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!enabled) return@Canvas
        val now = positionMs
        layout.draw(this, now, frameMs, paint, strokePaint)
    }
}

/**
 * 弹幕布局：预计算每条滚动弹幕的轨道，避免同轨道重叠。
 * 轨道数按高度动态算（每条约 28dp 高）。
 */
private class DanmakuLayout(private val all: List<Danmaku>) {
    // 每条滚动弹幕分配的轨道 index（-1=未分配）
    private val trackOf = mutableMapOf<Int, Int>()
    private var trackCount = 8

    // 二分找当前时间窗口的起始下标（按 timeMs 排序假定成立）
    private fun windowStart(nowMs: Long): Int {
        var lo = 0
        var hi = all.size
        val from = nowMs - SCROLL_MS // 6 秒前发出的还可能在屏幕上
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (all[mid].timeMs < from) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun draw(
        scope: DrawScope,
        nowMs: Long,
        frameMs: Long,
        paint: android.graphics.Paint,
        strokePaint: android.graphics.Paint,
    ) {
        val h = scope.size.height
        trackCount = maxOf(4, (h / 90f).toInt()) // 每轨道约 90px

        val start = windowStart(nowMs)
        var drawn = 0
        for (i in start until all.size) {
            val d = all[i]
            if (d.timeMs > nowMs + 1000) break // 只看到未来 1 秒
            if (drawn > 60) break // 单帧上限，防卡
            when (d.type) {
                Danmaku.TYPE_SCROLL -> {
                    val elapsed = nowMs - d.timeMs
                    if (elapsed < 0 || elapsed > SCROLL_MS) continue
                    val track = trackOf.getOrPut(i) { assignTrack(i, d.timeMs) }
                    val y = (track + 1) * (h / (trackCount + 1))
                    val textW = paint.measureText(d.text)
                    val totalW = scope.size.width + textW
                    val x = scope.size.width - totalW * (elapsed.toFloat() / SCROLL_MS)
                    drawDanmakuText(scope, d.text, x, y, d.color, paint, strokePaint)
                    drawn++
                }
                Danmaku.TYPE_TOP, Danmaku.TYPE_BOTTOM -> {
                    val elapsed = nowMs - d.timeMs
                    if (elapsed < 0 || elapsed > FIXED_MS) continue
                    val y = if (d.type == Danmaku.TYPE_TOP) {
                        120f + (i % 3) * 60f
                    } else {
                        h - 120f - (i % 3) * 60f
                    }
                    val textW = paint.measureText(d.text)
                    val x = (scope.size.width - textW) / 2
                    drawDanmakuText(scope, d.text, x, y, d.color, paint, strokePaint)
                    drawn++
                }
            }
        }
    }

    /** 简单轨道分配：找同轨道上一条已结束的（按发出时间+6秒估算） */
    private fun assignTrack(index: Int, timeMs: Long): Int {
        val lastEnd = LongArray(trackCount) { 0L }
        for ((idx, tr) in trackOf) {
            if (idx >= index) continue
            val t = all.getOrNull(idx)?.timeMs ?: continue
            if (t + SCROLL_MS > lastEnd[tr]) lastEnd[tr] = t + SCROLL_MS
        }
        var best = 0
        for (t in 1 until trackCount) {
            if (lastEnd[t] < lastEnd[best]) best = t
        }
        // 如果最优轨道的上一条还没播完（重叠），就用它（总比不显示好）
        return best
    }

    private fun drawDanmakuText(
        scope: DrawScope,
        text: String,
        x: Float,
        y: Float,
        color: Int,
        fill: android.graphics.Paint,
        stroke: android.graphics.Paint,
    ) {
        fill.color = color
        scope.drawContext.canvas.nativeCanvas.apply {
            drawText(text, x, y, stroke)
            drawText(text, x, y, fill)
        }
    }

    companion object {
        const val SCROLL_MS = 6000L
        const val FIXED_MS = 4000L
    }
}
