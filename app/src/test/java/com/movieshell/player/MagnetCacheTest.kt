package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MagnetCacheTest {
    private val day = 24L * 3600 * 1000
    private val now = 10 * day
    private fun e(n: String, mb: Long, ageDays: Long) = MagnetCache.Entry(n, mb shl 20, now - ageDays * day)

    @Test fun deletesExpired() {
        val r = MagnetCache.selectToDelete(listOf(e("a", 10, 5), e("b", 10, 1)), now, 1L shl 40, 3 * day, emptySet())
        assertEquals(listOf("a"), r)
    }
    @Test fun oldestFirstUntilUnderCap() {
        val r = MagnetCache.selectToDelete(listOf(e("new", 600, 0), e("old", 600, 2), e("mid", 600, 1)), now, 1000L shl 20, 3 * day, emptySet())
        assertEquals(listOf("old", "mid"), r)
    }
    @Test fun keepsPlaying() {
        val r = MagnetCache.selectToDelete(listOf(e("play", 5000, 9), e("x", 10, 0)), now, 100L shl 20, 3 * day, setOf("play"))
        assertEquals(listOf("x"), r)
    }
    @Test fun nothingWhenUnderCap() {
        assertEquals(emptyList<String>(), MagnetCache.selectToDelete(listOf(e("a", 1, 0)), now, 1L shl 30, 3 * day, emptySet()))
    }
}
