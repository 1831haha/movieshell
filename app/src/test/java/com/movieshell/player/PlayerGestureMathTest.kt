package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureMathTest {
    @Test
    fun dragRightSeeksForwardLeftBackward() {
        val dur = 40 * 60_000L // span = 10 min (capped)
        assertEquals(600_000L, PlayerGestureMath.scrubSpanMs(dur))
        assertEquals(60_000L + 300_000L, PlayerGestureMath.scrubTarget(60_000L, 500f, 1000f, dur))
        assertEquals(0L, PlayerGestureMath.scrubTarget(60_000L, -500f, 1000f, dur))
        assertEquals(dur, PlayerGestureMath.scrubTarget(dur - 1000, 1000f, 1000f, dur))
    }

    @Test
    fun shortVideoSpanAndHint() {
        assertEquals(60_000L, PlayerGestureMath.scrubSpanMs(30_000L))
        assertEquals("« 00:50 / 05:00（-00:10）", PlayerGestureMath.scrubHint(60_000L, 50_000L, 300_000L))
        assertEquals("» 1:00:00（+00:30）", PlayerGestureMath.scrubHint(3_570_000L, 3_600_000L, 0L))
    }

    @Test
    fun progressRatioRoundTripKeepsLongPrecision() {
        // v1.3.21 L12：比值驱动进度条——10 小时视频的毫秒不再进 Float。
        val dur = 10 * 3600_000L
        assertEquals(0.5f, PlayerGestureMath.progressRatioMs(5 * 3600_000L, dur))
        assertEquals(0f, PlayerGestureMath.progressRatioMs(0L, dur))
        assertEquals(0f, PlayerGestureMath.progressRatioMs(123L, 0L))
        assertEquals(1f, PlayerGestureMath.progressRatioMs(dur + 1000L, dur))
        // 往返误差只剩 Float 比值本身的尾数（10 小时约 2ms），不是毫秒转 Float 的丢精度。
        val pos = 12_345_678L
        val back = PlayerGestureMath.ratioToMs(PlayerGestureMath.progressRatioMs(pos, dur), dur)
        org.junit.Assert.assertTrue(kotlin.math.abs(back - pos) <= 5L)
        assertEquals(0L, PlayerGestureMath.ratioToMs(0.5f, 0L))
        assertEquals(dur, PlayerGestureMath.ratioToMs(1.5f, dur))
    }

    @Test
    fun scrubTargetLongVideoStaysExact() {
        // v1.3.21 L12：10 小时视频拖满一屏仍是整 10 分钟跨度，不被 Float 截断。
        val dur = 10 * 3600_000L
        assertEquals(600_000L + 60_000L, PlayerGestureMath.scrubTarget(60_000L, 1000f, 1000f, dur))
        assertEquals(dur, PlayerGestureMath.scrubTarget(dur - 1000L, 1000f, 1000f, dur))
        assertEquals(60_600L, PlayerGestureMath.scrubTarget(60_000L, 1f, 1000f, dur))
    }
}
