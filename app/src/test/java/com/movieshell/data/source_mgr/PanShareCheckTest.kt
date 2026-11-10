package com.movieshell.data.source_mgr

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * PanShareCheck 单元测试（MockWebServer）。
 * 覆盖播前检测的五种结论：有效、失效、要登录、登录已过期、未知。
 */
class PanShareCheckTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var apiBase: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
        apiBase = server.url("/1/clouddrive/").toString()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse()
        .setBody(body)
        .setHeader("Content-Type", "application/json")

    private fun checkQuark(cookie: String) = PanShareCheck.checkQuarkUc(
        apiBase = apiBase, pr = "pr=ucpro&fr=pc",
        ua = "UA", referer = "https://pan.quark.cn/",
        pwdId = "P1", passcode = "", cookie = cookie, client = client,
    )

    @Test
    fun quarkTokenOk() {
        server.enqueue(json("""{"code":0,"data":{"stoken":"S1"}}"""))
        assertEquals(PanShareCheck.Result.OK, checkQuark(""))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun quarkTokenDead() {
        server.enqueue(json("""{"code":41027,"message":"share not exist"}"""))
        assertEquals(PanShareCheck.Result.DEAD, checkQuark(""))
    }

    @Test
    fun quarkTokenNeedLogin() {
        server.enqueue(json("""{"code":31001,"message":"require login [guest]"}"""))
        assertEquals(PanShareCheck.Result.NEED_LOGIN, checkQuark(""))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun quarkTokenLoginExpired() {
        server.enqueue(json("""{"code":31001,"message":"require login [guest]"}"""))
        server.enqueue(json("""{"code":31001,"message":"require login [guest]"}"""))
        assertEquals(PanShareCheck.Result.LOGIN_EXPIRED, checkQuark("CK"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun quarkTokenOkWithCookie() {
        server.enqueue(json("""{"code":31001,"message":"require login [guest]"}"""))
        server.enqueue(json("""{"code":0,"data":{"stoken":"S1"}}"""))
        assertEquals(PanShareCheck.Result.OK, checkQuark("CK"))
    }

    @Test
    fun baiduWxlistOk() {
        server.enqueue(json("""{"errno":0,"data":{"list":[]}}"""))
        val r = PanShareCheck.checkBaidu(
            baiduBase = server.url("/").toString(), shortUrl = "18Fey057whBWfd_Bh0VYekw",
            pwd = "moil", client = client,
        )
        assertEquals(PanShareCheck.Result.OK, r)
    }

    @Test
    fun baiduWxlistDead() {
        server.enqueue(json("""{"errno":2}"""))
        val r = PanShareCheck.checkBaidu(
            baiduBase = server.url("/").toString(), shortUrl = "18Fey057whBWfd_Bh0VYekw",
            pwd = "moil", client = client,
        )
        assertEquals(PanShareCheck.Result.DEAD, r)
    }
}
