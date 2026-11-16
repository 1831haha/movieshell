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
}
