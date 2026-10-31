package com.movieshell.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscribePluginTest {
    @Test
    fun parsesAllThreeKindsAndDropsBadUrls() {
        val s = SubscribePlugin.parse(
            """{"vod":[{"name":"A","url":"https://a.com/c.json"},{"name":"bad","url":"ftp://x"}],
               "live":[{"url":"https://iptv.example/cn.m3u"},{"name":"dup","url":"https://iptv.example/cn.m3u"}],
               "book":[{"bookSourceName":"B","bookSourceUrl":"https://b.com"}]}""",
        )
        assertEquals(listOf("https://a.com/c.json"), s.vod.map { it.url })
        assertEquals(1, s.live.size)
        assertEquals("iptv.example", s.live[0].name)
        assertEquals(1, s.book.length())
    }

    @Test
    fun rejectsEmptyOrInvalid() {
        assertThrows(IllegalStateException::class.java) { SubscribePlugin.parse("not json") }
        assertThrows(IllegalStateException::class.java) { SubscribePlugin.parse("""{"vod":[]}""") }
    }
}
