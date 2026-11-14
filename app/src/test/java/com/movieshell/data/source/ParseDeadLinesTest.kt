package com.movieshell.data.source

import com.movieshell.domain.model.ParseConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.2.8：解析失败自动换线——ParseDeadLines 纯逻辑测试。
 * 不碰网络与 Android；时间用显式 now 参数控制，不依赖系统时钟。
 */
class ParseDeadLinesTest {

    private val video = "https://www.fofo33.com/dianying/155190"
    private val otherVideo = "https://www.fofo33.com/dianying/155191"
    private val deadUrl = "http://down.itufm.top/d/lanzou/iYp4c4b8x3sd/iTuFM.mp4"
    private val goodUrl = "https://cdn.example/movie.m3u8"

    @Before
    fun setUp() {
        ParseDeadLines.clear()
    }

    @After
    fun tearDown() {
        ParseDeadLines.clear()
    }

    @Test
    fun markDeadByResult_marksLineForThatVideoOnly() {
        ParseDeadLines.noteHit(video, deadUrl, "J芒果4k", "https://jx.example/mg.php", now = 1_000L)

        assertTrue(ParseDeadLines.markDeadByResult(deadUrl, now = 2_000L))
        assertEquals(setOf("J芒果4k"), ParseDeadLines.deadLinesFor(video, now = 2_000L))
        assertTrue(ParseDeadLines.deadLinesFor(otherVideo, now = 2_000L).isEmpty())
    }

    @Test
    fun markDeadByResult_unknownResult_returnsFalseAndMarksNothing() {
        assertFalse(ParseDeadLines.markDeadByResult(deadUrl, now = 1_000L))
        assertTrue(ParseDeadLines.deadLinesFor(video, now = 1_000L).isEmpty())
    }

    @Test
    fun markDeadByResult_expiredHit_returnsFalse() {
        ParseDeadLines.noteHit(video, deadUrl, "J芒果4k", "https://jx.example/mg.php", now = 0L)
        // 10 分钟 TTL 已过：命中记录过期，不记死
        assertFalse(ParseDeadLines.markDeadByResult(deadUrl, now = 10 * 60 * 1000L + 1L))
        assertTrue(ParseDeadLines.deadLinesFor(video, now = 10 * 60 * 1000L + 1L).isEmpty())
    }

    @Test
    fun deadLinesFor_expired_returnsEmpty() {
        ParseDeadLines.noteHit(video, deadUrl, "J芒果4k", "https://jx.example/mg.php", now = 0L)
        assertTrue(ParseDeadLines.markDeadByResult(deadUrl, now = 1_000L))
        // 记死后 10 分钟未到还在，过了就恢复
        assertEquals(setOf("J芒果4k"), ParseDeadLines.deadLinesFor(video, now = 9 * 60 * 1000L))
        assertTrue(ParseDeadLines.deadLinesFor(video, now = 11 * 60 * 1000L).isEmpty())
    }

    @Test
    fun noteHit_blankLineName_normalizedToJson() {
        ParseDeadLines.noteHit(video, deadUrl, "   ", "https://jx.example/mg.php", now = 1_000L)
        assertTrue(ParseDeadLines.markDeadByResult(deadUrl, now = 2_000L))
        assertEquals(setOf("JSON"), ParseDeadLines.deadLinesFor(video, now = 2_000L))
    }

    @Test
    fun withoutDeadLines_removesDeadKeepsOthers() {
        val lines = listOf(
            ParseConfig(name = "J芒果4k", url = "https://jx.example/mg.php", type = 1),
            ParseConfig(name = "光速", url = "https://jx.example/gs.php", type = 1),
            ParseConfig(name = "新浪", url = "https://jx.example/xl.php", type = 1),
        )
        val kept = ParseApi.withoutDeadLines(lines, setOf("J芒果4k"))
        assertEquals(listOf("光速", "新浪"), kept.map { it.name })
    }

    @Test
    fun withoutDeadLines_allDead_returnsOriginalList() {
        val lines = listOf(
            ParseConfig(name = "J芒果4k", url = "https://jx.example/mg.php", type = 1),
        )
        // 全记死时不留空：调用方回退到全部线路再试一次
        assertEquals(lines, ParseApi.withoutDeadLines(lines, setOf("J芒果4k")))
    }

    @Test
    fun withoutDeadLines_emptyDead_returnsSameList() {
        val lines = listOf(
            ParseConfig(name = "J芒果4k", url = "https://jx.example/mg.php", type = 1),
        )
        assertEquals(lines, ParseApi.withoutDeadLines(lines, emptySet()))
    }

    @Test
    fun deadLines_doNotAffectOtherVideoParse() {
        // 同一线路给 A 片回了死链，B 片重解析时该线路仍可用（作用域是单个视频地址）
        ParseDeadLines.noteHit(video, deadUrl, "J芒果4k", "https://jx.example/mg.php", now = 1_000L)
        assertTrue(ParseDeadLines.markDeadByResult(deadUrl, now = 2_000L))
        val lines = listOf(
            ParseConfig(name = "J芒果4k", url = "https://jx.example/mg.php", type = 1),
            ParseConfig(name = "光速", url = "https://jx.example/gs.php", type = 1),
        )
        assertEquals(lines, ParseApi.withoutDeadLines(lines, ParseDeadLines.deadLinesFor(otherVideo, now = 2_000L)))
        assertEquals(1, ParseApi.withoutDeadLines(lines, ParseDeadLines.deadLinesFor(video, now = 2_000L)).size)
    }
}
