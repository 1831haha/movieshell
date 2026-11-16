package com.movieshell.ui.actor

import com.movieshell.domain.model.Site
import com.movieshell.domain.model.Vod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v1.2.20：演员页流式增量去重的键——口径与旧 distinctBy 一致，
 * 只把“一次性全量去重”拆成“每到一个分组增量去重”。
 */
class ActorWorksDedupTest {

    private fun site(key: String) = Site(key = key, name = key, type = 1, api = "")
    private fun vod(name: String, year: String = "") = Vod(vodName = name, vodYear = year)

    @Test
    fun sameNameSameYear_dedupsAcrossSites() {
        val a = actorDedupKey(site("py:a"), vod("走马上任", "2026"))
        val b = actorDedupKey(site("py:b"), vod("走马上任", "2026"))
        assertEquals(a, b)
    }

    @Test
    fun sameNameDifferentYear_keepsBoth() {
        val a = actorDedupKey(site("py:a"), vod("走马上任", "2026"))
        val b = actorDedupKey(site("py:a"), vod("走马上任", "2025"))
        assertNotEquals(a, b)
    }

    @Test
    fun emptyYear_keepsPerSite() {
        val a = actorDedupKey(site("py:a"), vod("走马上任"))
        val b = actorDedupKey(site("py:b"), vod("走马上任"))
        assertNotEquals(a, b)
    }

    @Test
    fun emptyYear_sameSite_dedups() {
        val a = actorDedupKey(site("py:a"), vod("走马上任"))
        val b = actorDedupKey(site("py:a"), vod("走马上任"))
        assertEquals(a, b)
    }

    @Test
    fun nameIsTrimmedAndCaseInsensitive() {
        val a = actorDedupKey(site("py:a"), vod(" 走马上任 ", "2026"))
        val b = actorDedupKey(site("py:b"), vod("走马上任", "2026"))
        assertEquals(a, b)
    }
}
