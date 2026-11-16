package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCacheSettingsTest {

    private val dayMs = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L

    @Test fun `defaults keep old behavior`() {
        assertEquals(500, VideoCacheSettings.DEFAULT_CAP_MB)
        assertEquals(30, VideoCacheSettings.DEFAULT_MAX_AGE_DAYS)
        assertTrue(VideoCacheSettings.CAP_CHOICES_MB.contains(VideoCacheSettings.DEFAULT_CAP_MB))
        assertTrue(VideoCacheSettings.MAX_AGE_CHOICES_DAYS.contains(VideoCacheSettings.DEFAULT_MAX_AGE_DAYS))
    }

    @Test fun `capBytes converts MB to bytes`() {
        assertEquals(500L * 1024 * 1024, VideoCacheSettings.capBytes(500))
        assertEquals(1024L * 1024, VideoCacheSettings.capBytes(1))
        assertEquals(2000L * 1024 * 1024, VideoCacheSettings.capBytes(2000))
    }

    @Test fun `capBytes clamps invalid values to 1MB`() {
        assertEquals(1024L * 1024, VideoCacheSettings.capBytes(0))
        assertEquals(1024L * 1024, VideoCacheSettings.capBytes(-5))
    }

    @Test fun `maxAgeMs converts days to millis`() {
        assertEquals(30 * dayMs, VideoCacheSettings.maxAgeMs(30))
        assertEquals(7 * dayMs, VideoCacheSettings.maxAgeMs(7))
    }

    @Test fun `maxAgeMs returns null for unlimited`() {
        assertNull(VideoCacheSettings.maxAgeMs(0))
        assertNull(VideoCacheSettings.maxAgeMs(-1))
    }

    @Test fun `nextInCycle advances and wraps around`() {
        val choices = VideoCacheSettings.CAP_CHOICES_MB
        assertEquals(1000, VideoCacheSettings.nextInCycle(choices, 500))
        assertEquals(100, VideoCacheSettings.nextInCycle(choices, 2000))
        assertEquals(200, VideoCacheSettings.nextInCycle(choices, 100))
    }

    @Test fun `nextInCycle starts from first choice for unknown value`() {
        assertEquals(100, VideoCacheSettings.nextInCycle(VideoCacheSettings.CAP_CHOICES_MB, 300))
        assertEquals(7, VideoCacheSettings.nextInCycle(VideoCacheSettings.MAX_AGE_CHOICES_DAYS, 999))
    }

    @Test fun `nextInCycle keeps current value for empty choices`() {
        assertEquals(500, VideoCacheSettings.nextInCycle(emptyList(), 500))
    }

    @Test fun `isStale flags spans older than max age`() {
        val maxAge = 30 * dayMs
        assertTrue(VideoCacheSettings.isStale(now - 31 * dayMs, now, maxAge))
        assertFalse(VideoCacheSettings.isStale(now - 29 * dayMs, now, maxAge))
        assertFalse(VideoCacheSettings.isStale(now - dayMs, now, maxAge))
    }

    @Test fun `isStale is strict at the boundary`() {
        val maxAge = 30 * dayMs
        assertFalse(VideoCacheSettings.isStale(now - maxAge, now, maxAge))
        assertTrue(VideoCacheSettings.isStale(now - maxAge - 1, now, maxAge))
    }

    @Test fun `isStale never flags spans without a timestamp`() {
        val maxAge = 30 * dayMs
        assertFalse(VideoCacheSettings.isStale(0, now, maxAge))
        assertFalse(VideoCacheSettings.isStale(-1, now, maxAge))
        assertFalse(VideoCacheSettings.isStale(Long.MIN_VALUE, now, maxAge))
    }

    private data class Entry(val touch: Long)

    @Test fun `selectStale picks only expired entries`() {
        val items = listOf(
            Entry(now - 40 * dayMs),
            Entry(now - dayMs),
            Entry(now - 31 * dayMs),
            Entry(0),
        )
        val stale = VideoCacheSettings.selectStale(items, { it.touch }, now, 30 * dayMs)
        assertEquals(listOf(items[0], items[2]), stale)
    }

    @Test fun `selectStale picks nothing when unlimited`() {
        val items = listOf(Entry(now - 400 * dayMs))
        assertTrue(VideoCacheSettings.selectStale(items, { it.touch }, now, null).isEmpty())
    }

    @Test fun `formatCapMb shows MB below 1GB and GB above`() {
        assertEquals("500 MB", VideoCacheSettings.formatCapMb(500))
        assertEquals("1000 MB", VideoCacheSettings.formatCapMb(1000))
        assertEquals("1.0 GB", VideoCacheSettings.formatCapMb(1024))
        assertEquals("2.0 GB", VideoCacheSettings.formatCapMb(2000))
    }

    @Test fun `formatAge shows unlimited for zero`() {
        assertEquals("不限", VideoCacheSettings.formatAge(0))
        assertEquals("30 天", VideoCacheSettings.formatAge(30))
        assertEquals("7 天", VideoCacheSettings.formatAge(7))
    }
}
