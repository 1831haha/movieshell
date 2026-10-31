package com.movieshell.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.294.16：歌词 LRC 解析与同曲匹配（跨源兜底依赖这两处，不发网络）。
 */
class MusicApiLyricTest {

    @Test
    fun parseLrc_basicTimestamps() {
        val raw = """
            [00:12.00]第一行
            [01:02.5]第二行
            [ti:忽略无正文]
            无时间戳跳过
        """.trimIndent()
        val lines = MusicApi.parseLrc(raw)
        assertEquals(2, lines.size)
        assertEquals(12_000L, lines[0].timeMs)
        assertEquals("第一行", lines[0].text)
        // 5 → padEnd 3 → 500ms
        assertEquals(62_500L, lines[1].timeMs)
        assertEquals("第二行", lines[1].text)
    }

    @Test
    fun parseLrc_multiTimestampSameLine() {
        val raw = "[00:12.00][00:25.00]叠句"
        val lines = MusicApi.parseLrc(raw)
        assertEquals(2, lines.size)
        assertEquals(12_000L, lines[0].timeMs)
        assertEquals(25_000L, lines[1].timeMs)
        assertEquals("叠句", lines[0].text)
        assertEquals("叠句", lines[1].text)
    }

    @Test
    fun parseLrc_blankReturnsEmpty() {
        assertTrue(MusicApi.parseLrc("").isEmpty())
        assertTrue(MusicApi.parseLrc("   ").isEmpty())
    }

    @Test
    fun pickSameSong_prefersArtistOverlap() {
        val origin = MusicApi.Track(
            id = "tx:1",
            name = "遇上你是我的缘",
            artist = "石满仓",
            album = "",
            picId = "",
            urlId = "0018Qn9n00MloN",
            source = "tx",
        )
        val candidates = listOf(
            origin.copy(id = "wy:a", urlId = "1", source = "wy", artist = "央金兰泽"),
            origin.copy(id = "wy:b", urlId = "2", source = "wy", artist = "石满仓"),
            origin.copy(
                id = "wy:c",
                urlId = "3",
                source = "wy",
                name = "遇上你是我的缘(DJ版)",
                artist = "别人",
            ),
        )
        val hit = MusicApi.pickSameSong(origin, candidates)
        assertEquals("wy:b", hit?.id)
    }
}
