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
    fun lavfProbescore_acceptsHlsProbe() {
        // v1.3.18：mpv 默认认分线是 26，写 25 放宽门槛、无害。
        // 1.3.19 已证实 HLS 探测满分是 100（AVPROBE_SCORE_MAX），
        // 不是之前写的 25；分片问题与认分线无关，真正绕过靠
        // demuxer-lavf-format 强制（1.3.20）。
        assertEquals("25", MPV_LAVF_PROBESCORE)
    }

    @Test
    fun extractMpvProbeLines_findsProbeLinesIgnoresNoise() {
        val text = """
            [  58.362][v][vo/gpu] Testing FBO format rgba16f
            [  58.362][d][demux] Trying demuxers for level=normal.
            [  58.363][v][demux] Found 'hls' at score=100 size=2048.
            [  58.364][v][vo/gpu] Reported display depth: 8
            [  58.365][v][demux] Detected file format: hls (Playlist file)
        """.trimIndent()
        val out = extractMpvProbeLines(text)
        assertEquals(true, out.contains("Trying demuxers for level=normal"))
        assertEquals(true, out.contains("Found 'hls' at score=100"))
        assertEquals(true, out.contains("Detected file format: hls"))
        assertEquals(false, out.contains("Testing FBO"))
    }

    @Test
    fun extractMpvProbeLines_capsToMaxLinesAndEmpty() {
        assertEquals("", extractMpvProbeLines(""))
        val text = (1..10).joinToString("\n") { "No format found, try lowering probescore ($it)" }
        assertEquals(3, extractMpvProbeLines(text, maxLines = 3).split(" | ").size)
    }

    @Test
    fun mpvLogTailText_truncatesToMaxChars() {
        val text = "x".repeat(100)
        assertEquals("x".repeat(10), mpvLogTailText(text, maxLines = 5, maxChars = 10))
    }
}
