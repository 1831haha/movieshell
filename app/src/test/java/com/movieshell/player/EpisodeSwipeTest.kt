package com.movieshell.player

import org.junit.Assert.*
import org.junit.Test

class EpisodeSwipeTest {
    @Test fun zone() {
        assertTrue(EpisodeSwipe.inZone(700f, 1000)); assertTrue(EpisodeSwipe.inZone(600f, 1000))
        assertFalse(EpisodeSwipe.inZone(500f, 1000))
    }
    @Test fun portraitFullscreen() {
        assertTrue(EpisodeSwipe.isPortraitFullscreen(true, 1080, 2400))
        assertFalse(EpisodeSwipe.isPortraitFullscreen(true, 1080, 608))
        assertFalse(EpisodeSwipe.isPortraitFullscreen(false, 2400, 1080))
    }
    @Test fun direction() {
        assertEquals(1, EpisodeSwipe.direction(-150f, 100f))
        assertEquals(-1, EpisodeSwipe.direction(150f, 100f))
        assertEquals(0, EpisodeSwipe.direction(-50f, 100f))
    }
    @Test fun hintsAndTarget() {
        assertEquals("下一集 · 第3集", EpisodeSwipe.hint(1, 1, 5, false))
        assertEquals("已是最后一集", EpisodeSwipe.hint(1, 4, 5, true))
        assertEquals("已是第一集", EpisodeSwipe.hint(-1, 0, 5, true))
        assertEquals(null, EpisodeSwipe.target(1, 4, 5)); assertEquals(0, EpisodeSwipe.target(-1, 1, 5))
        assertTrue(EpisodeSwipe.isVerticalStart(2f, -10f)); assertFalse(EpisodeSwipe.isVerticalStart(10f, 2f))
    }
}
