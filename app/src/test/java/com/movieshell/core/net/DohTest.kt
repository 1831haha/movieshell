package com.movieshell.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.8 A4：DoH endpoint 校验与候选列表解析（纯函数，不真发 DoH 请求）。
 */
class DohTest {

    @Test
    fun validDefaultEndpoint() {
        assertTrue(Doh.isValidEndpoint(Doh.DEFAULT_ENDPOINT))
        assertEquals("https://doh.pub/dns-query", Doh.DEFAULT_ENDPOINT)
    }

    @Test
    fun validBuiltinCandidates() {
        assertEquals(
            listOf(
                "https://doh.pub/dns-query",
                "https://doh.360.cn/dns-query",
                "https://dns.alidns.com/dns-query",
            ),
            Doh.BUILTIN_ENDPOINTS,
        )
        Doh.BUILTIN_ENDPOINTS.forEach { assertTrue(it, Doh.isValidEndpoint(it)) }
    }

    @Test
    fun acceptsCustomHttpsWithPath() {
        assertTrue(Doh.isValidEndpoint("https://example.com/dns-query"))
        assertTrue(Doh.isValidEndpoint("https://example.com/dns-query?x=1"))
        assertTrue(Doh.isValidEndpoint("  https://doh.pub/dns-query  "))
    }

    @Test
    fun rejectsHttp() {
        assertFalse(Doh.isValidEndpoint("http://doh.pub/dns-query"))
    }

    @Test
    fun rejectsNoPath() {
        assertFalse(Doh.isValidEndpoint("https://doh.pub"))
        assertFalse(Doh.isValidEndpoint("https://doh.pub/"))
    }

    @Test
    fun rejectsGarbageAndBlank() {
        assertFalse(Doh.isValidEndpoint(""))
        assertFalse(Doh.isValidEndpoint("   "))
        assertFalse(Doh.isValidEndpoint("not a url"))
        assertFalse(Doh.isValidEndpoint("https://"))
        assertFalse(Doh.isValidEndpoint("doh.pub/dns-query"))
    }

    @Test
    fun resolveFallsBackToDefault() {
        assertEquals(Doh.DEFAULT_ENDPOINT, Doh.resolveEndpoint(null))
        assertEquals(Doh.DEFAULT_ENDPOINT, Doh.resolveEndpoint(""))
        assertEquals(Doh.DEFAULT_ENDPOINT, Doh.resolveEndpoint("   "))
        assertEquals(Doh.DEFAULT_ENDPOINT, Doh.resolveEndpoint("http://doh.pub/dns-query"))
        assertEquals(Doh.DEFAULT_ENDPOINT, Doh.resolveEndpoint("https://doh.pub"))
    }

    @Test
    fun resolveKeepsValidCustom() {
        assertEquals(
            "https://dns.alidns.com/dns-query",
            Doh.resolveEndpoint("https://dns.alidns.com/dns-query"),
        )
        assertEquals(
            "https://doh.360.cn/dns-query",
            Doh.resolveEndpoint("  https://doh.360.cn/dns-query "),
        )
    }
}
