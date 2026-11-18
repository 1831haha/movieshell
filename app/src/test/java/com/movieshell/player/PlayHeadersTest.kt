package com.movieshell.player

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHeadersTest {

    @Test fun `normalize canonicalizes and dedupes case insensitively`() {
        val h = PlayHeaders.normalize(mapOf("user-agent" to "UA1", "REFERER" to "https://r/", "x" to " ", "User-Agent" to "UA2"))
        assertEquals(setOf("User-Agent", "Referer"), h.keys)
        assertEquals("https://r/", h["Referer"])
    }

    @Test fun `merge primary wins fallback fills`() {
        val m = PlayHeaders.merge(
            mapOf("referer" to "https://sniff/"),
            mapOf("Referer" to "https://rule/", "Cookie" to "a=1", "User-Agent" to "RuleUA"),
        )
        assertEquals("https://sniff/", m["Referer"])
        assertEquals("a=1", m["Cookie"])
        assertEquals("RuleUA", m["User-Agent"])
        assertEquals(3, m.size)
    }

    @Test fun `get is case insensitive`() {
        assertEquals("v", PlayHeaders.get(mapOf("COOKIE" to "v"), "cookie"))
        assertNull(PlayHeaders.get(null, "cookie"))
    }

    @Test fun `withSiteDefaults adds Referer for bilivideo only when absent`() {
        val url = "https://upos-sz-estghw.bilivideo.com/upgcxcode/1/2/x.mp4?e=abc"
        val added = PlayHeaders.withSiteDefaults(url, emptyMap())
        assertEquals("https://www.bilibili.com", added["Referer"])
        val kept = PlayHeaders.withSiteDefaults(url, mapOf("referer" to "https://rule/"))
        assertEquals("https://rule/", kept["Referer"])
        assertEquals(1, kept.size)
        val other = PlayHeaders.withSiteDefaults("https://ppvod011.blbtgg.com/a/index.m3u8", emptyMap())
        assertTrue(other.isEmpty())
        val local = PlayHeaders.withSiteDefaults("/data/user/0/x/files/ad_filtered/a.m3u8", emptyMap())
        assertTrue(local.isEmpty())
    }

    @Test fun `fromJson object with non string values`() {
        val el = Json.parseToJsonElement("""{"User-Agent":"okhttp","x-num":12,"x-bool":true,"x-null":null}""")
        val h = PlayHeaders.fromJson(el)
        assertEquals("okhttp", h["User-Agent"])
        assertEquals("12", PlayHeaders.get(h, "x-num"))
        assertEquals("true", PlayHeaders.get(h, "x-bool"))
        assertNull(PlayHeaders.get(h, "x-null"))
    }

    @Test fun `fromJson json string`() {
        val el = Json.parseToJsonElement("\"{\\\"referer\\\":\\\"https://a/\\\"}\"")
        assertEquals("https://a/", PlayHeaders.fromJson(el)["Referer"])
    }

    @Test fun `fromJson null or garbage is empty`() {
        assertTrue(PlayHeaders.fromJson(null).isEmpty())
        assertTrue(PlayHeaders.fromJson(Json.parseToJsonElement("\"not json\"")).isEmpty())
    }
}
