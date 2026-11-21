package com.movieshell.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.9：已隐藏直播流（live_hidden_streams）纯函数单测，不碰 SharedPreferences。
 * 隐藏粒度按流 URL：同一条流挂多个台名下时删一次全干净；刷新聚合与缓存
 * 加载都过滤，隐藏后刷新不复活。
 */
class LiveStoreTest {

    private val dead = "http://45.192.97.170:8880/play/1695.m3u8"
    private val alive = "http://8.137.121.66:20241/608807420"

    @Test
    fun `parse empty or bad json yields empty set`() {
        assertTrue(LiveStore.parseHiddenStreamsJson(null).isEmpty())
        assertTrue(LiveStore.parseHiddenStreamsJson("").isEmpty())
        assertTrue(LiveStore.parseHiddenStreamsJson("not json").isEmpty())
        assertTrue(LiveStore.parseHiddenStreamsJson("{}").isEmpty())
        assertTrue(LiveStore.parseHiddenStreamsJson("[]").isEmpty())
    }

    @Test
    fun `round trip keeps urls and drops blanks and duplicates`() {
        val parsed = LiveStore.parseHiddenStreamsJson(
            LiveStore.serializeHiddenStreamsJson(listOf(dead, alive, dead)),
        )
        assertEquals(linkedSetOf(dead, alive), parsed)
    }

    @Test
    fun `filter drops hidden urls only`() {
        val channels = listOf(
            LiveChannel(id = "m3u:a|$dead", name = "CCTV1", url = dead),
            LiveChannel(id = "m3u:a|$alive", name = "CCTV1", url = alive),
            LiveChannel(id = "m3u:b|$dead", name = "CCTV2", url = dead),
        )
        val kept = LiveStore.filterHiddenChannels(channels, setOf(dead))
        assertEquals(listOf(channels[1]), kept)
    }

    @Test
    fun `filter with empty hidden set returns same list`() {
        val channels = listOf(LiveChannel(id = "m3u:a|$alive", name = "CCTV1", url = alive))
        assertEquals(channels, LiveStore.filterHiddenChannels(channels, emptySet()))
    }

    @Test
    fun `hostOfUrl extracts host with ip port and domain`() {
        assertEquals("45.192.97.170", LiveStore.hostOfUrl(dead))
        assertEquals("8.137.121.66", LiveStore.hostOfUrl(alive))
        assertEquals(
            "mediaserver.abnvideos.com",
            LiveStore.hostOfUrl("https://mediaserver.abnvideos.com/streams/abnchina.m3u8"),
        )
    }

    @Test
    fun `hostOfUrl returns empty for bad or schemeless input`() {
        assertEquals("", LiveStore.hostOfUrl(""))
        assertEquals("", LiveStore.hostOfUrl("not a url"))
        assertEquals("", LiveStore.hostOfUrl("just-a-path/1695.m3u8"))
    }
}
