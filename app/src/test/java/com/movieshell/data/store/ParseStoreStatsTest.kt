package com.movieshell.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.3.33 B-P2-6：解析统计单行解析（纯函数，不碰 SharedPreferences）。
 */
class ParseStoreStatsTest {

    @Test
    fun parseStatsLine_goodLine() {
        val parsed = ParseStore.parseStatsLine("http://x\t3\t1\t120")
        assertEquals("http://x", parsed?.first)
        assertEquals(ParseStore.Stat(3, 1, 120), parsed?.second)
    }

    @Test
    fun parseStatsLine_wrongFieldCount() {
        assertNull(ParseStore.parseStatsLine("http://x\t3\t1"))
        assertNull(ParseStore.parseStatsLine("http://x\t3\t1\t120\textra"))
        assertNull(ParseStore.parseStatsLine(""))
    }

    @Test
    fun parseStatsLine_badNumbers() {
        assertNull(ParseStore.parseStatsLine("http://x\tabc\t1\t120"))
        assertNull(ParseStore.parseStatsLine("http://x\t3\tNaN\t120"))
        assertNull(ParseStore.parseStatsLine("http://x\t3\t1\t12.5"))
    }

    @Test
    fun parseStatsLine_keyMayContainSlashes() {
        // key 本身含 / 与 ? 不影响按 \t 切分；只有 \t 才是分隔符。
        val parsed = ParseStore.parseStatsLine("https://jx.example.com/?url=\t0\t2\t0")
        assertEquals("https://jx.example.com/?url=", parsed?.first)
        assertEquals(ParseStore.Stat(0, 2, 0), parsed?.second)
    }
}
