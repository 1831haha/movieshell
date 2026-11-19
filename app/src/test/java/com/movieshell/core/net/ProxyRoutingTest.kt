package com.movieshell.core.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.26：代理选择器测试（纯 java.net，不碰 Android）。
 * 选择器只读注入的两个回调：回调说走代理才走，否则直连；服务没跑（地址为 null）一律直连。
 */
class ProxyRoutingTest {

    @After
    fun reset() {
        ProxyRouting.shouldProxyHost = { false }
        ProxyRouting.proxyAddress = { null }
    }

    private fun installJavBuzz() {
        ProxyRouting.shouldProxyHost = { it == "javbuzz.com" || it.endsWith(".javbuzz.com") }
        ProxyRouting.proxyAddress = { InetSocketAddress("127.0.0.1", 7891) }
    }

    @Test
    fun select_proxiesListedHost() {
        installJavBuzz()
        val proxies = ProxyRouting.selector.select(URI("https://javbuzz.com/en/works"))
        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.HTTP, proxies[0].type())
        assertEquals(InetSocketAddress("127.0.0.1", 7891), proxies[0].address())
    }

    @Test
    fun select_directForUnlistedHost() {
        installJavBuzz()
        val proxies = ProxyRouting.selector.select(URI("https://www.bilibili.com/"))
        assertEquals(listOf(Proxy.NO_PROXY), proxies)
    }

    @Test
    fun select_directWhenServiceDown() {
        installJavBuzz()
        ProxyRouting.proxyAddress = { null }
        val proxies = ProxyRouting.selector.select(URI("https://javbuzz.com/en/works"))
        assertEquals(listOf(Proxy.NO_PROXY), proxies)
    }

    @Test
    fun select_directForNoHostUri() {
        installJavBuzz()
        val proxies = ProxyRouting.selector.select(URI("file:///tmp/x.m3u8"))
        assertEquals(listOf(Proxy.NO_PROXY), proxies)
    }

    @Test
    fun select_defaultIsDirect() {
        // 注入前默认全部直连（XiaYingApp 启动时才注入）。
        val proxies = ProxyRouting.selector.select(URI("https://javbuzz.com/"))
        assertTrue(proxies.all { it == Proxy.NO_PROXY })
    }
}
