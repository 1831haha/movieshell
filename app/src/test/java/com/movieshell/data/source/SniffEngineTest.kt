package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SniffEngine 单元测试（全纯函数，不碰网络/WebView）。
 */
class SniffEngineTest {

    @Test
    fun `isPlayableDirectUrl hits m3u8 mp4 mpd aac`() {
        assertTrue(SniffEngine.isPlayableDirectUrl("https://x/y.m3u8"))
        assertTrue(SniffEngine.isPlayableDirectUrl("https://x/y.MP4"))
        assertTrue(SniffEngine.isPlayableDirectUrl("https://x/y.m3u8?token=abc"))
        assertTrue(SniffEngine.isPlayableDirectUrl("https://x/y.mpd"))
        assertTrue(SniffEngine.isPlayableDirectUrl("https://x/y.aac"))
        assertFalse(SniffEngine.isPlayableDirectUrl("https://x/y.html"))
        assertFalse(SniffEngine.isPlayableDirectUrl("https://x/api.json"))
        assertFalse(SniffEngine.isPlayableDirectUrl("https://x/y.png"))
    }

    @Test
    fun `sniffKind detects rtmp`() {
        assertEquals(SniffEngine.SniffKind.RTMP, SniffEngine.sniffKind("rtmp://x/live/1"))
        assertEquals(SniffEngine.SniffKind.RTMP, SniffEngine.sniffKind("https://x/y.rtmp"))
        assertEquals(SniffEngine.SniffKind.DIRECT, SniffEngine.sniffKind("https://x/y.m3u8"))
        assertNull(SniffEngine.sniffKind("https://x/index.html"))
    }

    @Test
    fun `isSuspiciousMediaUrl is conservative`() {
        // 无后缀但像媒体地址 → 值得探测
        assertTrue(SniffEngine.isSuspiciousMediaUrl("https://x/play?id=123"))
        assertTrue(SniffEngine.isSuspiciousMediaUrl("https://x/api/stream/abc"))
        assertTrue(SniffEngine.isSuspiciousMediaUrl("https://x/vod/1"))
        // 静态资源 → 不探测
        assertFalse(SniffEngine.isSuspiciousMediaUrl("https://x/a.png"))
        assertFalse(SniffEngine.isSuspiciousMediaUrl("https://x/b.js"))
        assertFalse(SniffEngine.isSuspiciousMediaUrl("https://x/c.css"))
        assertFalse(SniffEngine.isSuspiciousMediaUrl("https://x/d.json"))
        // 无关键词的普通页面 → 不探测
        assertFalse(SniffEngine.isSuspiciousMediaUrl("https://x/about/us"))
        // 非 http(s) → 不探测
        assertFalse(SniffEngine.isSuspiciousMediaUrl("rtmp://x/live/1"))
        assertFalse(SniffEngine.isSuspiciousMediaUrl("file:///sdcard/a.html"))
    }

    @Test
    fun `contentTypeKind maps mime types`() {
        val k = SniffEngine.SniffKind.DIRECT
        assertEquals(k, SniffEngine.contentTypeKind("application/vnd.apple.mpegurl"))
        assertEquals(k, SniffEngine.contentTypeKind("application/x-mpegurl; charset=utf-8"))
        assertEquals(k, SniffEngine.contentTypeKind("application/dash+xml"))
        assertEquals(k, SniffEngine.contentTypeKind("video/mp4"))
        assertEquals(k, SniffEngine.contentTypeKind("audio/aac"))
        assertEquals(k, SniffEngine.contentTypeKind("audio/mpeg"))
        assertNull(SniffEngine.contentTypeKind("text/html"))
        assertNull(SniffEngine.contentTypeKind("image/jpeg"))
        assertNull(SniffEngine.contentTypeKind(null))
        assertNull(SniffEngine.contentTypeKind(""))
    }

    @Test
    fun `extractIframeSrcs pulls srcs and resolves`() {
        val html = """
            <html><body>
            <iframe src="https://p1.com/play.html" width="100%"></iframe>
            <IFRAME SRC='/embed/2'></iframe>
            <iframe src="javascript:void(0)"></iframe>
            <iframe></iframe>
            </body></html>
        """.trimIndent()
        val srcs = SniffEngine.extractIframeSrcs(html, "https://host.com/a/b.html")
        assertEquals(listOf("https://p1.com/play.html", "https://host.com/embed/2"), srcs)
    }

    @Test
    fun `resolveUrl handles relative forms`() {
        assertEquals(
            "https://h.com/embed/2",
            SniffEngine.resolveUrl("https://h.com/a/b.html", "/embed/2"),
        )
        assertEquals(
            "https://h.com/a/c.html",
            SniffEngine.resolveUrl("https://h.com/a/b.html", "c.html"),
        )
        assertEquals(
            "http://cdn.com/x.js",
            SniffEngine.resolveUrl("http://h.com/a.html", "//cdn.com/x.js"),
        )
        assertEquals(
            "rtmp://h.com/live",
            SniffEngine.resolveUrl("https://h.com/a.html", "rtmp://h.com/live"),
        )
    }
}
