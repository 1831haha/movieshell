package com.movieshell.player

import com.movieshell.player.danmaku.DanmakuSource
import com.movieshell.player.danmaku.DanmakuSource.CacheFileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.33 B-P2-4：弹幕缓存定期清理选择逻辑（纯函数，不碰磁盘）。
 */
class DanmakuCacheCleanTest {

    private fun info(name: String, ageMs: Long, size: Long, now: Long = 10_000L) =
        CacheFileInfo(name, now - ageMs, size)

    @Test
    fun selectToDelete_nothingExpiredUnderCaps() {
        val files = listOf(info("a", 1000L, 100), info("b", 2000L, 100))
        assertTrue(DanmakuSource.selectCacheFilesToDelete(files, 10_000L, 7L * 24 * 60 * 60 * 1000, 1000, 256L * 1024 * 1024).isEmpty())
    }

    @Test
    fun selectToDelete_expiredOnly() {
        val day = 24L * 60 * 60 * 1000
        val files = listOf(info("old", 8 * day, 100), info("new", 1 * day, 100))
        val out = DanmakuSource.selectCacheFilesToDelete(files, 10_000L, 7 * day, 1000, 256L * 1024 * 1024)
        assertEquals(listOf("old"), out.map { it.name })
    }

    @Test
    fun selectToDelete_overFileCountDropsOldestFirst() {
        val files = listOf(info("a", 3000L, 100), info("b", 2000L, 100), info("c", 1000L, 100))
        val out = DanmakuSource.selectCacheFilesToDelete(files, 10_000L, 7L * 24 * 60 * 60 * 1000, 2, Long.MAX_VALUE)
        assertEquals(listOf("a"), out.map { it.name })
    }

    @Test
    fun selectToDelete_overTotalBytesDropsOldestUntilUnder() {
        val files = listOf(info("a", 3000L, 400), info("b", 2000L, 400), info("c", 1000L, 400))
        val out = DanmakuSource.selectCacheFilesToDelete(files, 10_000L, 7L * 24 * 60 * 60 * 1000, 1000, 800L)
        assertEquals(listOf("a"), out.map { it.name })
    }

    @Test
    fun selectToDelete_expiredAndOverCapCombined() {
        val day = 24L * 60 * 60 * 1000
        val files = listOf(info("old", 8 * day, 100), info("a", 3000L, 100), info("b", 2000L, 100), info("c", 1000L, 100))
        val out = DanmakuSource.selectCacheFilesToDelete(files, 10_000L, 7 * day, 2, Long.MAX_VALUE)
        assertEquals(listOf("old", "a"), out.map { it.name })
    }
}
