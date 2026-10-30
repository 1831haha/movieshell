package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayUrlNormalizeTest {
    @Test fun stripsEpisodeNamePrefix() {
        assertEquals(
            "https://fengbao13.com/video/abc/index.m3u8",
            normalizeResolvedPlayUrl("HD\$https://fengbao13.com/video/abc/index.m3u8"),
        )
        assertEquals(
            "https://bfikuncdn.com/20261003/cO39Nngq/index.m3u8",
            normalizeResolvedPlayUrl("正片\$https://bfikuncdn.com/20261003/cO39Nngq/index.m3u8"),
        )
    }

    @Test fun usesPartAfterLastDollar() {
        assertEquals(
            "https://a.com/1.m3u8",
            normalizeResolvedPlayUrl("暴风@0@HD\$https://a.com/1.m3u8"),
        )
        assertEquals("magnet:?xt=urn:btih:abc", normalizeResolvedPlayUrl("第1集\$magnet:?xt=urn:btih:abc"))
        assertEquals("rtmp://live/x", normalizeResolvedPlayUrl("直播\$rtmp://live/x"))
    }

    @Test fun keepsValidUrlsContainingDollar() {
        val u = "https://a.com/play?sig=x\$y&name=HD\$https://b.com"
        assertEquals(u, normalizeResolvedPlayUrl(u))
        assertEquals("https://a.com/1.mp4", normalizeResolvedPlayUrl("  https://a.com/1.mp4 "))
        assertEquals("/sdcard/a.mp4", normalizeResolvedPlayUrl("/sdcard/a.mp4"))
    }

    @Test fun leavesUnrecognizedAlone() {
        // 不是地址的仍原样交给校验拒绝，不放宽
        assertEquals("HD\$abc", normalizeResolvedPlayUrl("HD\$abc"))
        assertEquals("NBY-XMYAES", normalizeResolvedPlayUrl("NBY-XMYAES"))
        assertEquals("HD\$", normalizeResolvedPlayUrl("HD\$"))
        assertEquals("", normalizeResolvedPlayUrl(""))
        assertEquals("pics://a", normalizeResolvedPlayUrl("pics://a"))
    }
}
