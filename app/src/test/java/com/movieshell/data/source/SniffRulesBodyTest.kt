package com.movieshell.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffRulesBodyTest {
    @Test fun `body regex detection`() {
        assertTrue(SniffRules.isBodyRegex("#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:6.4,[\\s\\S]*?#EXT-X-DISCONTINUITY"))
        assertFalse(SniffRules.isBodyRegex("ad\\.m3u8"))
    }

    @Test fun `host wildcard`() {
        assertTrue(SniffRules.hostMatches("*", "a.com"))
        assertTrue(SniffRules.hostMatches("ffzy", "v.ffzy-play.com"))
        assertTrue(SniffRules.hostMatches("*.lz*.com", "cdn.lzcdn.com"))
        assertFalse(SniffRules.hostMatches("*.lz*.com", "cdn.abc.net"))
    }

    @Test fun `clear media suffix`() {
        assertTrue(SniffEngine.hasClearMediaSuffix("https://a.com/x/index.m3u8?t=1"))
        assertTrue(SniffEngine.hasClearMediaSuffix("https://a.com/x/1.mp4"))
        assertFalse(SniffEngine.hasClearMediaSuffix("https://a.com/play?id=3"))
        assertFalse(SniffEngine.hasClearMediaSuffix("https://a.com/m3u8/get"))
    }
}
