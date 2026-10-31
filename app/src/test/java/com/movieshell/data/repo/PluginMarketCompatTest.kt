package com.movieshell.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginMarketCompatTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResource(name)!!.readText(Charsets.UTF_8)

    private val dsUrl = "https://raw.githubusercontent.com/hjdhnx/dsplayer-plugin/main/market.json"
    private val dsBase = "https://raw.githubusercontent.com/hjdhnx/dsplayer-plugin/main/"

    @Test fun resolveRelative() {
        assertEquals(dsBase + "packages/a.json", PluginMarket.resolveUrl("packages/a.json", dsBase))
        assertEquals(dsBase + "packages/a.json", PluginMarket.resolveUrl("./packages/a.json", dsBase))
        assertEquals(dsBase + "packages/a.json", PluginMarket.resolveUrl("/packages/a.json", dsBase))
        assertEquals("https://raw.githubusercontent.com/hjdhnx/dsplayer-plugin/x.zip",
            PluginMarket.resolveUrl("../x.zip", dsBase))
        assertEquals("https://cdn.example.com/x.zip", PluginMarket.resolveUrl("//cdn.example.com/x.zip", dsBase))
        assertEquals("http://h/x.zip", PluginMarket.resolveUrl("http://h/x.zip", dsBase))
    }

    @Test fun marketBaseVariants() {
        assertEquals(dsBase, PluginMarket.marketBase(dsUrl))
        assertEquals(dsBase, PluginMarket.marketBase("$dsUrl?t=1"))
        assertEquals("https://m.example.com/r/", PluginMarket.marketBase(dsUrl, "https://m.example.com/r"))
        assertEquals(dsBase + "dist/", PluginMarket.marketBase(dsUrl, "dist/"))
        assertEquals("https://ghfast.top/https://raw.githubusercontent.com/hjdhnx/dsplayer-plugin/main/packages/a.zip",
            PluginMarket.resolveUrl("packages/a.zip", PluginMarket.marketBase("https://ghfast.top/$dsUrl")))
    }

    @Test fun parseDsPlayerMarket() {
        val info = PluginMarket.parseMarket(dsUrl, res("dsplayer-market.json"))
        assertTrue(info.plugins.size > 30)
        info.plugins.forEach {
            assertTrue("${it.id}: ${it.url}", it.url.startsWith("https://"))
            assertTrue(it.iconUrl.isEmpty() || it.iconUrl.startsWith("https://"))
        }
        val iptv = info.plugins.first { it.id == "iptv-ccsh" }
        assertEquals("live", iptv.type)
        assertEquals(dsBase + "packages/iptv-ccsh-1.1.0.json", iptv.url)
        assertTrue(iptv.isSupported())
        assertTrue(info.plugins.first { it.id == "demo-sources" }.isSupported())
        assertFalse(info.plugins.first { it.id == "mediaProxy" }.isSupported()) // import
        assertFalse(info.plugins.first { it.id == "ijk" }.isSupported()) // apk
        assertFalse(info.plugins.first { it.id == "mediaProxy-win" }.isSupported()) // win32 only
        assertEquals("https://github.com/hjdhnx/dsplayer-plugin/releases/download/idmplus-18.2/idmplus-18.2-CN.apk",
            info.plugins.first { it.id == "idmplus" }.url)
        assertEquals(dsBase + "icons/market.png", info.logo)
    }

    @Test fun parseOwnMarketStillWorks() {
        val url = "https://raw.githubusercontent.com/1831haha/xiaying-plugins/main/market.json"
        val info = PluginMarket.parseMarket(url, res("xiaying-market.json"))
        assertTrue(info.plugins.isNotEmpty())
        info.plugins.forEach { assertTrue(it.url, it.url.startsWith("http")) }
    }
}
