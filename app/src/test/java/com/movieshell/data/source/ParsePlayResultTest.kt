package com.movieshell.data.source

import com.movieshell.data.repo.SpiderEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsePlayResultTest {

    @Test fun `spider play json keeps parse jx header and playUrl prefix`() {
        val r = SpiderEngine.parsePlayJson(
            """{"parse":"1","jx":0,"url":"https://site/p.html","playUrl":"json:https://jx/?url=","header":"{\"user-agent\":\"UA\",\"referer\":\"https://site/\"}"}"""
        )
        assertEquals(1, r.parse)
        assertEquals("https://site/p.html", r.url)
        assertEquals("json:https://jx/?url=", r.playUrl)
        assertEquals("UA", r.header["User-Agent"])
        assertEquals("https://site/", r.header["Referer"])
    }

    @Test fun `spider playUrl prefix never used as url`() {
        val r = SpiderEngine.parsePlayJson("""{"parse":1,"url":"","playUrl":"https://jx.page/?v="}""")
        assertEquals("", r.url)
    }

    @Test fun `spider media playUrl fills blank url`() {
        val r = SpiderEngine.parsePlayJson("""{"parse":0,"url":"","playUrl":"https://cdn/a.m3u8"}""")
        assertEquals("https://cdn/a.m3u8", r.url)
    }

    @Test fun `spider header with numeric values`() {
        val r = SpiderEngine.parsePlayJson("""{"url":"https://cdn/a.m3u8","header":{"X-Id":5,"Referer":"https://r/"}}""")
        assertEquals("5", r.header["X-Id"])
    }

    @Test fun `parse api extractHit reads url and headers`() {
        val api = ParseApi()
        val (u, h) = api.extractHit(
            """{"code":200,"url":"https://cdn/x.m3u8","header":{"referer":"https://jx/"},"user-agent":"JXUA"}""",
            "https://v.qq.com/x.html",
        )!!
        assertEquals("https://cdn/x.m3u8", u)
        assertEquals("https://jx/", h["Referer"])
        assertEquals("JXUA", h["User-Agent"])
    }

    @Test fun `parse api extractHit data string and protocol relative`() {
        val api = ParseApi()
        assertEquals("https://cdn/y.mp4", api.extractHit("""{"data":"//cdn/y.mp4"}""", "")!!.first)
        assertEquals("https://cdn/z.m3u8", api.extractHit("""{"data":{"url":"https://cdn/z.m3u8"}}""", "")!!.first)
    }

    @Test fun `parse api extractHit rejects echo and vip page`() {
        val api = ParseApi()
        assertNull(api.extractHit("""{"url":"https://v.qq.com/x.html"}""", "https://v.qq.com/x.html"))
        assertNull(api.extractHit("""{"url":"https://www.iqiyi.com/v_1.html"}""", "https://other"))
        assertNull(api.extractHit("""{"url":"not a url"}""", ""))
        assertNull(api.extractHit("garbage", ""))
    }
}
