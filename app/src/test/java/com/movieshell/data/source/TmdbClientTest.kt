package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.2.13：TMDB 文件名解析纯函数测试（去扩展名、去画质词、去集数、提年份）。 */
class TmdbClientTest {

    @Test
    fun `chinese movie with year in parens`() {
        val parsed = TmdbClient.parseFileName("震耳欲聋 (2025).mkv")
        assertEquals("震耳欲聋", parsed?.title)
        assertEquals(2025, parsed?.year)
    }

    @Test
    fun `english release name strips quality and codec words`() {
        val parsed = TmdbClient.parseFileName("The.Matrix.1999.2160p.BluRay.x264.mkv")
        assertEquals("The Matrix", parsed?.title)
        assertEquals(1999, parsed?.year)
    }

    @Test
    fun `series episode marker stripped`() {
        val parsed = TmdbClient.parseFileName("Breaking.Bad.S01E01.1080p.WEB-DL.mkv")
        assertEquals("Breaking Bad", parsed?.title)
        assertNull(parsed?.year)
    }

    @Test
    fun `chinese title with year and quality word`() {
        val parsed = TmdbClient.parseFileName("流浪地球2 2023 4K.mp4")
        assertEquals("流浪地球2", parsed?.title)
        assertEquals(2023, parsed?.year)
    }

    @Test
    fun `chinese episode marker stripped`() {
        val parsed = TmdbClient.parseFileName("狂飙 第12集.mp4")
        assertEquals("狂飙", parsed?.title)
        assertNull(parsed?.year)
    }

    @Test
    fun `year takes last occurrence`() {
        val parsed = TmdbClient.parseFileName("1942.2012.1080p.mkv")
        assertEquals("1942", parsed?.title)
        assertEquals(2012, parsed?.year)
    }

    @Test
    fun `bracketed group info stripped`() {
        val parsed = TmdbClient.parseFileName("[高清] 震耳欲聋 2025.mp4")
        assertEquals("震耳欲聋", parsed?.title)
        assertEquals(2025, parsed?.year)
    }

    @Test
    fun `only year and quality returns null`() {
        assertNull(TmdbClient.parseFileName("2025.1080p.mkv"))
    }

    @Test
    fun `only episode marker returns null`() {
        assertNull(TmdbClient.parseFileName("S01E01.mkv"))
    }

    @Test
    fun `plain name without year`() {
        val parsed = TmdbClient.parseFileName("狂飙.mp4")
        assertEquals("狂飙", parsed?.title)
        assertNull(parsed?.year)
    }

    @Test
    fun `poster url uses w500`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            TmdbClient.posterUrl("/abc.jpg"),
        )
    }
}
