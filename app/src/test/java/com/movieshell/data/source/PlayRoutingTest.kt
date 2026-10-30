package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRoutingTest {

    private val vip = PlayRouting.DEFAULT_VIP_FLAGS

    @Test fun `media url plays directly even with parse 1`() {
        val r = PlayRouting.decide(RawPlay(url = "https://cdn.x.com/a/index.m3u8", parse = 1), "lzm3u8", vip)
        assertEquals(PlayRoute.Direct("https://cdn.x.com/a/index.m3u8"), r)
    }

    @Test fun `jx 1 forces parse list even for media`() {
        val r = PlayRouting.decide(RawPlay(url = "https://cdn.x.com/a.mp4", jx = 1), "", vip)
        assertTrue(r is PlayRoute.JxList)
    }

    @Test fun `parse 1 non vip goes to sniff page`() {
        val r = PlayRouting.decide(RawPlay(url = "https://www.somesite.com/play/1-1.html", parse = 1), "线路1", vip)
        assertEquals(PlayRoute.SniffPage("https://www.somesite.com/play/1-1.html"), r)
    }

    @Test fun `parse 1 vip flag goes to parse list`() {
        val r = PlayRouting.decide(RawPlay(url = "https://www.somesite.com/x.html", parse = 1), "qq", vip)
        assertTrue(r is PlayRoute.JxList)
    }

    @Test fun `parse 1 vip host goes to parse list`() {
        val r = PlayRouting.decide(RawPlay(url = "https://v.qq.com/x/cover/abc.html", parse = 1), "", vip)
        assertTrue(r is PlayRoute.JxList)
    }

    @Test fun `parse 0 vip host goes to parse list`() {
        val r = PlayRouting.decide(RawPlay(url = "https://www.iqiyi.com/v_19rr.html"), "", emptyList())
        assertTrue(r is PlayRoute.JxList)
    }

    @Test fun `parse 0 suffixless stream stays direct`() {
        val u = "https://stream.example.com/play?id=123&token=abc"
        assertEquals(PlayRoute.Direct(u), PlayRouting.decide(RawPlay(url = u), "", vip))
    }

    @Test fun `parse 0 html page goes to sniff page`() {
        val r = PlayRouting.decide(RawPlay(url = "https://www.foo.com/vod/play/1.html"), "foo", vip)
        assertTrue(r is PlayRoute.SniffPage)
    }

    @Test fun `empty url is empty direct`() {
        assertEquals(PlayRoute.Direct(""), PlayRouting.decide(RawPlay(url = " "), "", vip))
    }

    @Test fun `playUrl json prefix`() {
        val r = PlayRouting.decide(RawPlay(url = "https://site/p.html", parse = 1, playUrl = "json:https://jx.api/?url="), "", vip)
        assertEquals(PlayRoute.JsonPrefix("https://jx.api/?url=", "https://site/p.html"), r)
    }

    @Test fun `playUrl parse prefix`() {
        val r = PlayRouting.decide(RawPlay(url = "https://site/p.html", parse = 1, playUrl = "parse:W3"), "", vip)
        assertEquals(PlayRoute.NamedParse("W3", "https://site/p.html"), r)
    }

    @Test fun `playUrl http prefix is prefix sniff`() {
        val r = PlayRouting.decide(RawPlay(url = "abc123", parse = 1, playUrl = "https://jx.page/?v="), "", vip)
        assertEquals(PlayRoute.PrefixSniff("https://jx.page/?v=", "abc123"), r)
    }

    @Test fun `site playUrl used when rule has none`() {
        val r = PlayRouting.decide(RawPlay(url = "https://site/p.html", parse = 1), "", vip, sitePlayUrl = "parse:A")
        assertEquals(PlayRoute.NamedParse("A", "https://site/p.html"), r)
    }

    @Test fun `unknown prefix ignored`() {
        assertNull(PlayRouting.parsePrefix("ftp:abc", "u"))
        assertNull(PlayRouting.parsePrefix("json:notaurl", "u"))
        assertNull(PlayRouting.parsePrefix("", "u"))
    }

    @Test fun `media url detection`() {
        assertTrue(PlayRouting.isMediaUrl("https://a.com/x.m3u8?t=1"))
        assertTrue(PlayRouting.isMediaUrl("https://a.com/x.mp4"))
        assertFalse(PlayRouting.isMediaUrl("https://a.com/x.html"))
    }

    @Test fun `order lines by flag then score then index`() {
        data class L(val n: String, val flags: List<String>, val score: Double)
        val lines = listOf(
            L("a", emptyList(), 0.5),
            L("b", listOf("youku"), 0.9),
            L("c", listOf("qq"), 0.1),
            L("d", emptyList(), 0.8),
        )
        val out = PlayRouting.orderLines(lines, "qq", { it.flags }, { it.score }).map { it.n }
        assertEquals(listOf("c", "d", "a", "b"), out)
    }

    @Test fun `order keeps original order without flag and scores`() {
        val out = PlayRouting.orderLines(listOf("x", "y", "z"), "", { emptyList() })
        assertEquals(listOf("x", "y", "z"), out)
    }
}
