package com.movieshell.player

import org.junit.Assert.*
import org.junit.Test

class EpisodePrefetchTest {
    @Test fun nextEpisodeNormal() {
        assertEquals(2 to 3, EpisodePrefetch.nextEpisodeToPrefetch(2, 2, 10, false))
    }

    @Test fun nextEpisodeLastIsNull() {
        assertNull(EpisodePrefetch.nextEpisodeToPrefetch(0, 9, 10, false))
        assertNull(EpisodePrefetch.nextEpisodeToPrefetch(0, 0, 1, false))
    }

    @Test fun nextEpisodeLiveOrEmptyIsNull() {
        assertNull(EpisodePrefetch.nextEpisodeToPrefetch(0, 0, 10, true))
        assertNull(EpisodePrefetch.nextEpisodeToPrefetch(0, 0, 0, false))
    }

    @Test fun usableWhenMatchingAndFresh() {
        val cached = EpisodePrefetch.PrefetchedEpisode(0, 3, "https://x/3.m3u8", 1000L)
        assertTrue(EpisodePrefetch.isPrefetchUsable(cached, 0, 3, 1000L + 60_000L))
    }

    @Test fun unusableWhenEpisodeMismatch() {
        val cached = EpisodePrefetch.PrefetchedEpisode(0, 3, "https://x/3.m3u8", 1000L)
        assertFalse(EpisodePrefetch.isPrefetchUsable(cached, 0, 4, 2000L))
        assertFalse(EpisodePrefetch.isPrefetchUsable(cached, 1, 3, 2000L))
    }

    @Test fun unusableWhenExpiredOrBlank() {
        val cached = EpisodePrefetch.PrefetchedEpisode(0, 3, "https://x/3.m3u8", 1000L)
        assertFalse(EpisodePrefetch.isPrefetchUsable(cached, 0, 3, 1000L + EpisodePrefetch.MAX_AGE_MS + 1))
        assertFalse(EpisodePrefetch.isPrefetchUsable(cached.copy(url = ""), 0, 3, 2000L))
        assertFalse(EpisodePrefetch.isPrefetchUsable(null, 0, 3, 2000L))
    }

    @Test fun boundaryExactlyMaxAgeIsUsable() {
        val cached = EpisodePrefetch.PrefetchedEpisode(0, 3, "https://x/3.m3u8", 1000L)
        assertTrue(EpisodePrefetch.isPrefetchUsable(cached, 0, 3, 1000L + EpisodePrefetch.MAX_AGE_MS))
    }
}
