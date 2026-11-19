package com.movieshell.player

import com.movieshell.player.danmaku.DanmakuSource
import com.movieshell.player.danmaku.normalizeDanmakuHost
import com.movieshell.player.danmaku.parseCustomDanmakuHosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.2.22：自定义弹幕接口——地址归一化（只取 host）与 bangumi 集数配对（纯函数）。 */
class DanmakuCustomHostsTest {

    @Test fun `full url with page route keeps host only`() {
        assertEquals(
            listOf("dm.999.team", "danmu.cynn.top"),
            parseCustomDanmakuHosts("https://dm.999.team/87654321\nhttps://danmu.cynn.top/cynnsq"),
        )
    }

    @Test fun `bare host kept and lowercased`() {
        assertEquals(
            listOf("dm.999.team"),
            parseCustomDanmakuHosts("DM.999.TEAM"),
        )
    }

    @Test fun `duplicates removed in order`() {
        assertEquals(
            listOf("dm.999.team", "dmfl.us.ci"),
            parseCustomDanmakuHosts("dm.999.team\ndmfl.us.ci\ndm.999.team"),
        )
    }

    @Test fun `invalid lines dropped`() {
        assertEquals(
            listOf("dm.999.team"),
            parseCustomDanmakuHosts("notahost\n\nhttp://\ndm.999.team\nfoo bar"),
        )
    }

    @Test fun `port stripped`() {
        assertEquals(
            listOf("dm.999.team"),
            parseCustomDanmakuHosts("dm.999.team:8080"),
        )
    }

    @Test fun `cap at ten hosts`() {
        val raw = (1..12).joinToString("\n") { "h$it.example.com" }
        val parsed = parseCustomDanmakuHosts(raw)
        assertEquals(10, parsed.size)
        assertEquals("h1.example.com", parsed.first())
        assertEquals("h10.example.com", parsed.last())
    }

    @Test fun `empty in empty out`() {
        assertTrue(parseCustomDanmakuHosts("").isEmpty())
        assertTrue(parseCustomDanmakuHosts("  \n ").isEmpty())
    }

    @Test fun `pickEpisodeId exact number match wins`() {
        val body = """{"episodes":[
            {"episodeId":11,"episodeNumber":"1"},
            {"episodeId":22,"episodeNumber":"2"}
        ]}"""
        assertEquals(22L, DanmakuSource.pickEpisodeId(body, 2))
    }

    @Test fun `pickEpisodeId numeric episodeNumber also works`() {
        val body = """{"episodes":[{"episodeId":11,"episodeNumber":1}]}"""
        assertEquals(11L, DanmakuSource.pickEpisodeId(body, 1))
    }

    @Test fun `pickEpisodeId out of range falls back to first`() {
        val body = """{"episodes":[
            {"episodeId":11,"episodeNumber":"1"},
            {"episodeId":22,"episodeNumber":"2"}
        ]}"""
        assertEquals(11L, DanmakuSource.pickEpisodeId(body, 5))
    }

    @Test fun `pickEpisodeId empty or malformed returns null`() {
        assertNull(DanmakuSource.pickEpisodeId("""{"episodes":[]}""", 1))
        assertNull(DanmakuSource.pickEpisodeId("not json", 1))
    }

    @Test fun `normalizeDanmakuHost keeps host only`() {
        assertEquals("dandan.example.com", normalizeDanmakuHost("https://dandan.example.com/api/v2/"))
        assertEquals("dandan.example.com", normalizeDanmakuHost("DANDAN.EXAMPLE.COM:8080"))
        assertEquals("dandan.example.com", normalizeDanmakuHost("dandan.example.com."))
    }

    @Test fun `normalizeDanmakuHost rejects invalid`() {
        assertNull(normalizeDanmakuHost(""))
        assertNull(normalizeDanmakuHost("notahost"))
        assertNull(normalizeDanmakuHost("http://"))
        assertNull(normalizeDanmakuHost("a..b.com"))
        assertNull(normalizeDanmakuHost("foo bar"))
    }
}
