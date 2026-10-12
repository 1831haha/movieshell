package com.github.catvod.crawler

import okhttp3.Dns
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v0.10.2：宿主 Spider 必须提供 jar 通过 invoke-static 调用的 safeDns()/client()，
 * 以及实例方法 initApi(extend)（dex 全量扫描确认，缺失即 NoSuchMethodError）。
 */
class SpiderHostTest {

    @Test
    fun `safeDns returns system dns`() {
        assertNotNull(Spider.safeDns())
    }

    @Test
    fun `client returns shared okhttp client`() {
        assertNotNull(Spider.client())
        assertSame(Spider.client(), Spider.client())
    }

    @Test
    fun `initApi default is no-op`() {
        Spider().initApi("anything")
    }

    @Test
    fun `dns lookup works`() {
        val dns = Spider.safeDns()
        assertNotNull(dns)
        // Dns.SYSTEM 能解析 localhost，不依赖外网
        assertNotNull(dns.lookup("localhost"))
    }
}
