package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvPlayerManagerTest {

    @Test
    fun mpvLogTailText_blankReturnsEmpty() {
        assertEquals("", mpvLogTailText(""))
        assertEquals("", mpvLogTailText("   \n  \n"))
    }

    @Test
    fun mpvLogTailText_takesLastLines() {
        val text = (1..50).joinToString("\n") { "line$it" }
        assertEquals("line48\nline49\nline50", mpvLogTailText(text, maxLines = 3))
    }

    @Test
    fun mpvLogTailText_dropsBlankLines() {
        assertEquals("a\nb", mpvLogTailText("a\n\nb\n\n", maxLines = 10))
    }

    @Test
    fun protocolWhitelist_allowsHttpsSegments() {
        // v1.3.16：本地 m3u8 的 https 分片必须在白名单里，否则分片逐个被拒；
        // file/crypto/data 是 mpv 播本地文件时的默认白名单，不可丢。
        val parts = MPV_PROTOCOL_WHITELIST.split(",")
        assertEquals(true, parts.contains("https"))
        assertEquals(true, parts.contains("http"))
        assertEquals(true, parts.contains("file"))
        assertEquals(true, parts.contains("crypto"))
        assertEquals(true, parts.contains("data"))
    }

    @Test
    fun mpvLogTailText_truncatesToMaxChars() {
        val text = "x".repeat(100)
        assertEquals("x".repeat(10), mpvLogTailText(text, maxLines = 5, maxChars = 10))
    }
}
