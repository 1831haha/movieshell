package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun parseLrc_badTimestampSkipsOnlyThatLine() {
        // v1.3.33 B-P2-1：一行坏时间戳只跳过该行，不再整首无歌词。
        val raw = """
            [99999999999999999999:00]坏行
            [00:12.00]好行
        """.trimIndent()
        val lines = MusicApi.parseLrc(raw)
        assertEquals(1, lines.size)
        assertEquals(12_000L, lines[0].timeMs)
        assertEquals("好行", lines[0].text)
    }

    @Test
    fun parseLrc_overflowMinuteSkipped() {
        // 分钟数本身合法（toLongOrNull 能过），但 min*60000 回绕，必须丢弃该行。
        val raw = """
            [999999999999999999:00]溢出
            [00:12.00]好行
        """.trimIndent()
        val lines = MusicApi.parseLrc(raw)
        assertEquals(1, lines.size)
        assertEquals("好行", lines[0].text)
    }

    @Test
    fun parseLrc_secondOver59Skipped() {
        val raw = """
            [00:60.00]越界秒
            [00:12.00]好行
        """.trimIndent()
        val lines = MusicApi.parseLrc(raw)
        assertEquals(1, lines.size)
        assertEquals("好行", lines[0].text)
    }

    @Test
    fun parseLrc_badTimestampOnOneOfManyStampsSkipsOnlyThatStamp() {
        val raw = "[00:12.00][99999999999999999999:00]叠句"
        val lines = MusicApi.parseLrc(raw)
        assertEquals(1, lines.size)
        assertEquals(12_000L, lines[0].timeMs)
        assertEquals("叠句", lines[0].text)
    }

    @Test
    fun lrcTimeMs_overflowReturnsNull() {
        val maxMin = (Long.MAX_VALUE - 60_000L) / 60_000L
        assertEquals(maxMin * 60_000L, MusicApi.lrcTimeMs(maxMin, 0, 0))
        assertNull(MusicApi.lrcTimeMs(maxMin + 1, 0, 0))
        assertNull(MusicApi.lrcTimeMs(-1, 0, 0))
        assertEquals(12_000L, MusicApi.lrcTimeMs(0, 12, 0))
        assertEquals(62_500L, MusicApi.lrcTimeMs(1, 2, 500))
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
