package com.movieshell.data.source_mgr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.3.26：clash 代理纯函数测试（不碰 Android：normalizeHost/hostOf/
 * extractRuleHost/countProxies 都不读 prefs 与文件）。
 */
class ClashProxyTest {

    @Test
    fun normalizeHost_lowercasesStripsPortAndTrailingDot() {
        assertEquals("javbuzz.com", ClashProxy.normalizeHost("JavBuzz.COM"))
        assertEquals("a.b.com", ClashProxy.normalizeHost("a.b.com:8080"))
        assertEquals("a.b.com", ClashProxy.normalizeHost("a.b.com."))
    }

    @Test
    fun normalizeHost_rejectsBadInput() {
        assertNull(ClashProxy.normalizeHost(""))
        assertNull(ClashProxy.normalizeHost("localhost"))
        assertNull(ClashProxy.normalizeHost("a b.com"))
        assertNull(ClashProxy.normalizeHost("a_b.com"))
    }

    @Test
    fun hostOf_extractsFromUrl() {
        assertEquals("javbuzz.com", ClashProxy.hostOf("https://javbuzz.com/api/search?q=1"))
        assertEquals("cdn.foo.com", ClashProxy.hostOf("https://cdn.foo.com:8443/x.m3u8"))
        assertNull(ClashProxy.hostOf("not a url"))
        assertNull(ClashProxy.hostOf("file:///tmp/x.m3u8"))
    }

    @Test
    fun extractRuleHost_readsDrpyHostField() {
        val js = "var rule = {title: 'JavBuzz', host: 'https://javbuzz.com', url: '/api/search'};"
        assertEquals("javbuzz.com", ClashProxy.extractRuleHost(js))
        val js2 = "var rule = {\"host\":\"https://fourhoi.com\",\"title\":\"x\"};"
        assertEquals("fourhoi.com", ClashProxy.extractRuleHost(js2))
        assertNull(ClashProxy.extractRuleHost("var rule = {title: 'x'};"))
    }

    @Test
    fun countProxies_countsBlockStyleNodesOnlyInProxiesSection() {
        val yaml = """
            mixed-port: 7890
            proxies:
              - name: "节点A"
                type: vmess
                server: 1.2.3.4
              - name: "节点B"
                type: trojan
                server: 5.6.7.8
            proxy-groups:
              - name: PROXY
                type: url-test
                proxies:
                  - 节点A
        """.trimIndent()
        assertEquals(2, ClashProxy.countProxies(yaml))
    }

    @Test
    fun countProxies_flowStyleAndMissingSection() {
        val yaml = "proxies:\n  - {name: a, type: vmess}\n  - {name: b, type: trojan}\n"
        assertEquals(2, ClashProxy.countProxies(yaml))
        assertEquals(0, ClashProxy.countProxies("mixed-port: 7890\nrules:\n  - MATCH,DIRECT\n"))
    }
}
