package com.movieshell.player

import com.movieshell.player.PlayEndMode.PlayEndAction
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayEndModeTest {
    @Test fun sequentialGoesNextThenPausesAtLast() {
        assertEquals(PlayEndAction.PlayNext(3), PlayEndMode.action(0, 2, 10))
        assertEquals(PlayEndAction.Pause, PlayEndMode.action(0, 9, 10))
        assertEquals(PlayEndAction.Pause, PlayEndMode.action(0, 0, 1))
    }

    @Test fun singleLoops() {
        assertEquals(PlayEndAction.ReplayCurrent, PlayEndMode.action(1, 9, 10))
        assertEquals(PlayEndAction.ReplayCurrent, PlayEndMode.action(1, 0, 1))
    }

    @Test fun listLoopsBackToFirst() {
        assertEquals(PlayEndAction.PlayNext(3), PlayEndMode.action(2, 2, 10))
        assertEquals(PlayEndAction.PlayNext(0), PlayEndMode.action(2, 9, 10))
    }

    @Test fun stopModeAlwaysPauses() {
        assertEquals(PlayEndAction.Pause, PlayEndMode.action(3, 2, 10))
        assertEquals(PlayEndAction.Pause, PlayEndMode.action(3, 9, 10))
        assertEquals(PlayEndAction.Pause, PlayEndMode.action(3, 0, 1))
    }

    @Test fun emptyEpisodeListAlwaysPauses() {
        assertEquals(PlayEndAction.Pause, PlayEndMode.action(2, 0, 0))
        assertEquals(PlayEndAction.Pause, PlayEndMode.action(0, 0, 0))
    }

    @Test fun unknownModeFallsBackToSequential() {
        assertEquals(PlayEndAction.PlayNext(1), PlayEndMode.action(99, 0, 5))
        assertEquals(PlayEndAction.Pause, PlayEndMode.action(-1, 4, 5))
    }

    @Test fun computeEndAtMs_collapsedDurationNeverTriggers() {
        assertEquals(Long.MAX_VALUE, PlayEndMode.computeEndAtMs(4000L, 0))
        assertEquals(Long.MAX_VALUE, PlayEndMode.computeEndAtMs(1999L, 90))
        assertEquals(Long.MAX_VALUE, PlayEndMode.computeEndAtMs(0L, 0))
    }

    @Test fun computeEndAtMs_aboveThresholdTriggersNormally() {
        assertEquals(5000L, PlayEndMode.computeEndAtMs(6000L, 0))
        assertEquals(5000L, PlayEndMode.computeEndAtMs(6000L, 90))
    }

    @Test fun computeEndAtMs_skipOutroWithinDuration() {
        assertEquals(2_700_000L - 90_000L, PlayEndMode.computeEndAtMs(2_700_000L, 90))
        assertEquals(2_700_000L - 1000L, PlayEndMode.computeEndAtMs(2_700_000L, 0))
    }

    @Test fun computeEndAtMs_skipOutroNotShorterThanDurationFallsBack() {
        assertEquals(60_000L - 1000L, PlayEndMode.computeEndAtMs(60_000L, 90))
        assertEquals(60_000L - 1000L, PlayEndMode.computeEndAtMs(60_000L, 60))
    }
}
