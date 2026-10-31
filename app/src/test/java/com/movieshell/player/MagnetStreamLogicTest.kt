package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetStreamLogicTest {
    @Test
    fun picksLargestVideoOverBiggerNonVideo() {
        val files = listOf(
            MagnetStreamLogic.FileCand(0, "readme.txt", 9_000_000),
            MagnetStreamLogic.FileCand(1, "movie.mkv", 2_000_000),
            MagnetStreamLogic.FileCand(2, "sample.mp4", 500_000),
        )
        assertEquals("movie.mkv", MagnetStreamLogic.pickPlayFile(files)!!.name)
    }

    @Test
    fun fallsBackToLargestWhenNoVideoExt() {
        val files = listOf(
            MagnetStreamLogic.FileCand(0, "a.bin", 100),
            MagnetStreamLogic.FileCand(1, "b.bin", 999),
        )
        assertEquals("b.bin", MagnetStreamLogic.pickPlayFile(files)!!.name)
    }

    @Test
    fun parseRange() {
        assertEquals(MagnetStreamLogic.Range(0, 99), MagnetStreamLogic.parseRange("bytes=0-99", 1000))
        assertEquals(MagnetStreamLogic.Range(100, 999), MagnetStreamLogic.parseRange("bytes=100-", 1000))
        assertNull(MagnetStreamLogic.parseRange(null, 1000))
        assertNull(MagnetStreamLogic.parseRange("bytes=500-10", 100))
    }

    @Test
    fun enrichMagnetAppendsTrackersOnce() {
        val raw = "magnet:?xt=urn:btih:4A824D15AD0CC3C9AAB6AB9EA70F47EAE1D5D331&dn=demo"
        val once = MagnetStreamLogic.enrichMagnet(raw)
        assertTrue(once.contains("tr="))
        assertTrue(once.startsWith(raw))
        assertEquals(once, MagnetStreamLogic.enrichMagnet(once))
    }

    @Test
    fun enrichMagnetKeepsNonMagnet() {
        assertEquals("http://x", MagnetStreamLogic.enrichMagnet("http://x"))
    }

    @Test
    fun infoHashHex() {
        assertEquals(
            "4a824d15ad0cc3c9aab6ab9ea70f47eae1d5d331",
            MagnetStreamLogic.infoHashHex("magnet:?xt=urn:btih:4A824D15AD0CC3C9AAB6AB9EA70F47EAE1D5D331&dn=x"),
        )
        assertNull(MagnetStreamLogic.infoHashHex("http://no"))
    }

    @Test
    fun statusTimeoutMentionsNetwork() {
        val t = MagnetStreamLogic.statusTimeout(60)
        assertTrue(t.contains("60"))
        assertTrue(t.contains("DHT") || t.contains("tracker"))
    }
}
