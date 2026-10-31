package com.movieshell.player

import com.movieshell.player.MusicQueueLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicQueueLogicTest {
    private val r0: (Int) -> Int = { 0 }

    @Test fun nextSequentialWraps() {
        assertEquals(1, MusicQueueLogic.nextIndex(3, 0, Mode.ALL, r0))
        assertEquals(0, MusicQueueLogic.nextIndex(3, 2, Mode.ALL, r0))
    }

    @Test fun manualNextInRepeatOneStillAdvances() {
        assertEquals(2, MusicQueueLogic.nextIndex(5, 1, Mode.ONE, r0))
    }

    @Test fun shuffleNextNeverRepeatsCurrent() {
        for (cur in 0 until 5) for (r in 0 until 4) {
            val n = MusicQueueLogic.nextIndex(5, cur, Mode.SHUFFLE) { r }
            assertNotEquals(cur, n)
            assertTrue(n in 0 until 5)
        }
    }

    @Test fun shuffleSingleTrackStaysPut() {
        assertEquals(0, MusicQueueLogic.nextIndex(1, 0, Mode.SHUFFLE, r0))
        assertEquals(0, MusicQueueLogic.autoNextIndex(1, 0, Mode.SHUFFLE, r0))
    }

    @Test fun invalidCurrentStartsFromEnds() {
        assertEquals(0, MusicQueueLogic.nextIndex(4, -1, Mode.ALL, r0))
        assertEquals(3, MusicQueueLogic.prevIndex(4, -1, Mode.ALL, emptyList()))
        assertEquals(-1, MusicQueueLogic.nextIndex(0, 0, Mode.ALL, r0))
    }

    @Test fun prevSequentialWraps() {
        assertEquals(0, MusicQueueLogic.prevIndex(3, 1, Mode.ALL, emptyList()))
        assertEquals(2, MusicQueueLogic.prevIndex(3, 0, Mode.ONE, emptyList()))
    }

    @Test fun shufflePrevUsesHistory() {
        assertEquals(4, MusicQueueLogic.prevIndex(6, 1, Mode.SHUFFLE, listOf(2, 4)))
        // 历史为空时退回顺序上一首
        assertEquals(0, MusicQueueLogic.prevIndex(6, 1, Mode.SHUFFLE, emptyList()))
        // 历史里是当前项或越界的忽略
        assertEquals(3, MusicQueueLogic.prevIndex(6, 1, Mode.SHUFFLE, listOf(3, 1, 9)))
    }

    @Test fun autoNextByMode() {
        assertEquals(2, MusicQueueLogic.autoNextIndex(5, 2, Mode.ONE, r0))
        assertEquals(3, MusicQueueLogic.autoNextIndex(5, 2, Mode.ALL, r0))
        assertEquals(0, MusicQueueLogic.autoNextIndex(5, 4, Mode.ALL, r0))
        assertNotEquals(2, MusicQueueLogic.autoNextIndex(5, 2, Mode.SHUFFLE) { 2 })
    }

    @Test fun prevRestartsOnlyAfterThreeSecondsOfLoadedTrack() {
        assertTrue(MusicQueueLogic.shouldRestartOnPrev(3_001, trackLoaded = true))
        assertFalse(MusicQueueLogic.shouldRestartOnPrev(3_000, trackLoaded = true))
        assertFalse(MusicQueueLogic.shouldRestartOnPrev(60_000, trackLoaded = false))
    }

    @Test fun skipFollowsDirectionAndWraps() {
        assertEquals(3, MusicQueueLogic.skipIndex(5, 2, 1))
        assertEquals(0, MusicQueueLogic.skipIndex(5, 4, 1))
        assertEquals(4, MusicQueueLogic.skipIndex(5, 0, -1))
    }

    @Test fun skipChainVisitsEveryOtherTrackExactlyOnce() {
        val size = 6
        var cur = 2
        val seen = mutableListOf<Int>()
        repeat(MusicQueueLogic.maxSkipAttempts(size)) {
            cur = MusicQueueLogic.skipIndex(size, cur, 1)
            seen += cur
        }
        assertEquals(listOf(3, 4, 5, 0, 1), seen)
        assertEquals(0, MusicQueueLogic.maxSkipAttempts(1))
        assertEquals(0, MusicQueueLogic.maxSkipAttempts(0))
    }
}
