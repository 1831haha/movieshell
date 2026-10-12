package com.movieshell.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Java spider 运行时纯函数测试（v0.8.0）：映射、缓存路径、播放 JSON 解析。 */
class SpiderEngineTest {

    @Test
    fun `csp_ api maps to catvod spider class`() {
        assertEquals(
            "com.github.catvod.spider.Mp4Mov",
            SpiderEngine.classNameForApi("csp_Mp4Mov")
        )
        assertEquals(
            "com.github.catvod.spider.AppYsV2",
            SpiderEngine.classNameForApi("csp_AppYsV2")
        )
    }

    @Test
    fun `class mapping strips prefix case-insensitively`() {
        assertEquals(
            "com.github.catvod.spider.Mp4Mov",
            SpiderEngine.classNameForApi("CSP_Mp4Mov")
        )
    }

    @Test
    fun `jar cache name is deterministic and filesystem-safe`() {
        val sub = "https://raw.githubusercontent.com/vcloudc/tvbox/main/tw/api.json"
        val jar = "https://raw.githubusercontent.com/vcloudc/tvbox/main/tw/tvkj.jar"
        val name = JarSpiderStore.cacheName(sub, jar)
        assertTrue(name.endsWith("_tvkj.jar"))
        assertTrue(name.matches(Regex("[A-Za-z0-9._-]+")))
        assertEquals(name, JarSpiderStore.cacheName(sub, jar))
    }

    @Test
    fun `jar url resolves relative to subscription`() {
        val sub = "https://raw.githubusercontent.com/vcloudc/tvbox/main/tw/api.json"
        assertEquals(
            "https://raw.githubusercontent.com/vcloudc/tvbox/main/tw/tvkj.jar",
            JarSpiderStore.resolveJarUrl(sub, "./tvkj.jar")
        )
        assertEquals(
            "https://cdn.example.com/a.jar",
            JarSpiderStore.resolveJarUrl(sub, "https://cdn.example.com/a.jar")
        )
        assertEquals("", JarSpiderStore.resolveJarUrl(sub, ""))
        assertEquals("", JarSpiderStore.resolveJarUrl(sub, "   "))
    }

    @Test
    fun `parsePlayJson reads parse url header`() {
        val r = SpiderEngine.parsePlayJson(
            """{"parse":1,"url":"https://x/y.m3u8","header":{"User-Agent":"UA","Referer":"https://x/"}}"""
        )
        assertEquals(1, r.parse)
        assertEquals("https://x/y.m3u8", r.url)
        assertEquals("UA", r.header["User-Agent"])
        assertEquals("https://x/", r.header["Referer"])
    }

    @Test
    fun `parsePlayJson accepts bare url`() {
        val r = SpiderEngine.parsePlayJson("https://x/y.mp4")
        assertEquals("https://x/y.mp4", r.url)
        assertEquals(0, r.parse)
        assertTrue(r.header.isEmpty())
    }

    @Test
    fun `parsePlayJson tolerates garbage`() {
        val r = SpiderEngine.parsePlayJson("not json {{{")
        assertEquals("", r.url)
        assertEquals(0, r.parse)
    }
}
