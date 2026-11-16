package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v1.2.21：历史去重身份——“源 + 剧 + 集”，不是播放地址。
 * 实证：同一源同一电影因每次拉取换签名地址，连占 3 张继续观看卡。
 */
class HistoryDedupTest {

    private fun key(
        sourceType: String = "php",
        sourceKey: String = "金牌 dz",
        vodId: String = "123",
        episodeIndex: Int = 0,
        url: String = "https://cdn.example.com/a.m3u8?sign=1",
    ) = historyIdentityKey(sourceType, sourceKey, vodId, episodeIndex, url)

    @Test
    fun sameIdentity_differentSignedUrls_merge() {
        // 同一源同一剧同一集，只是地址签名换了——必须并成一条
        val a = key(url = "https://cdn.example.com/a.m3u8?sign=1")
        val b = key(url = "https://cdn.example.com/a.m3u8?sign=2")
        assertEquals(a, b)
    }

    @Test
    fun differentEpisode_staysSeparate() {
        assertNotEquals(key(episodeIndex = 0), key(episodeIndex = 1))
    }

    @Test
    fun differentSource_staysSeparate() {
        // 不同源的同名 vodId（如两个 CMS 站都用数字 id）不能误并
        assertNotEquals(key(sourceKey = "金牌 dz"), key(sourceKey = "另一源"))
        assertNotEquals(key(sourceType = "php"), key(sourceType = "py"))
    }

    @Test
    fun blankVodId_fallsBackToUrl() {
        val a = historyIdentityKey("php", "金牌 dz", "", -1, "https://cdn.example.com/a.m3u8")
        val b = historyIdentityKey("php", "金牌 dz", "", -1, "https://cdn.example.com/a.m3u8")
        val c = historyIdentityKey("php", "金牌 dz", "", -1, "https://cdn.example.com/b.m3u8")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
