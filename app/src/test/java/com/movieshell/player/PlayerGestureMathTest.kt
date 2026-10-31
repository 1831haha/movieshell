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
}
