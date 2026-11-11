package com.movieshell.data.source_mgr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PanQrLogin 单元测试（MockWebServer + 录制的请求断言）。
 * 覆盖取 token/轮询/换取/二维码内容拼接与百度/阿里解析；真机扫码、
 * 百度 unicast 真实网络、UC 结构验证仍待真机逐项验，这里只保证
 * 解析与状态机逻辑。
 */
class PanQrLoginTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
        base = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse()
        .setBody(body)
        .setHeader("Content-Type", "application/json")

    // ---------------- 夸克 / UC ----------------

    @Test
    fun quarkTokenFetch() {
        // v1.1.27：先预热一次取 _UP_* 会话 Cookie，再正式取 token——
        // 要排两个响应；正式取 token 的请求须带业务站 Referer。
        // v1.1.29：每次请求都带 client_id＋v＋全新 request_id（官方 bundle 的 uop 默认参数）。
        server.enqueue(json("""{"status":2000000,"message":"ok","data":{"members":{"token":"warmup"}}}"""))
        server.enqueue(json("""{"status":2000000,"message":"ok","data":{"members":{"token":"tok123"}}}"""))
        assertEquals("tok123", PanQrLogin.fetchQuarkToken(client, base, referer = "https://pan.quark.cn/"))
        val warmup = server.takeRequest()
        val real = server.takeRequest()
        for (req in listOf(warmup, real)) {
            val path = req.path.orEmpty()
            assertTrue(path.contains("/cas/ajax/getTokenForQrcodeLogin?"))
            assertTrue(path.contains("client_id=532"))
            assertTrue(path.contains("v=1.2"))
            assertTrue(path.contains("request_id="))
        }
        assertEquals("https://pan.quark.cn/", real.getHeader("Referer"))
        // 两次请求各带一个全新 request_id，不能复用同一个。
        val rid = { p: String -> p.substringAfter("request_id=").substringBefore("&") }
        assertTrue(rid(warmup.path.orEmpty()) != rid(real.path.orEmpty()))
    }

    @Test
    fun quarkPollSuccessAndNotYet() {
        server.enqueue(json("""{"status":2000000,"data":{"members":{"service_ticket":"ST-1"}}}"""))
        assertEquals("ST-1", PanQrLogin.pollQuarkTicket(client, base, "tok123"))
        server.enqueue(json("""{"status":4000000,"message":"not scanned"}"""))
        assertNull(PanQrLogin.pollQuarkTicket(client, base, "tok123"))
    }

    @Test
    fun quarkQrContent() {
        val content = PanQrLogin.buildQuarkQrContent("tok123")
        assertTrue(content.startsWith("https://su.quark.cn/4_eMHBJ?token=tok123&client_id=532&ssb=weblogin"))
    }

    @Test
    fun ucQrContent() {
        val content = PanQrLogin.buildUcQrContent("tok123")
        assertTrue(content.startsWith("https://broccoli.uc.cn/apps/ZY5qIZiK/routes/1hbcQwLhH?"))
        assertTrue(content.contains("client_id=381"))
        assertTrue(content.contains("token=tok123"))
    }

    @Test
    fun quarkTicketExchange() {
        // account/info 成功＋Set-Cookie 落 __puus（MockWebServer 侧不用 Domain，
        // 否则 jar 按域匹配不到 127.0.0.1；线上 Cookie 带 Domain=.quark.cn）。
        server.enqueue(
            json("""{"success":true,"data":{}}""")
                .setHeader("Set-Cookie", "__puus=PUUS1; Path=/"),
        )
        val cookie = PanQrLogin.exchangeUopTicket(client, base, "ST-1", "UA")
        assertEquals("__puus=PUUS1", cookie)
    }

    @Test
    fun quarkTicketExchangeFailsWithoutPuus() {
        server.enqueue(json("""{"success":false,"code":401,"msg":"bad ticket"}"""))
        server.enqueue(MockResponse().setBody("<html></html>"))
        assertNull(PanQrLogin.exchangeUopTicket(client, base, "ST-BAD", "UA"))
    }

    @Test
    fun quarkTicketExchangeUsesSessionJar() {
        // v1.1.30：换取必须带取 token 的同一个会话 jar（_UP_* Cookie 跟着走）、
        // 带 lw=scan 与业务站 Referer；只落 __pus 也算换取成功。
        val jar = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)
        jar.cookieStore.add(
            java.net.URI(base),
            java.net.HttpCookie("_UP_X", "SESS1"),
        )
        server.enqueue(
            json("""{"success":true,"data":{}}""")
                .setHeader("Set-Cookie", "__pus=PUS1; Path=/"),
        )
        val cookie = PanQrLogin.exchangeUopTicket(
            client, base, "ST-1", "UA", jar = jar, referer = "https://pan.quark.cn/",
        )
        assertTrue(cookie?.contains("__pus=PUS1") == true)
        assertTrue(cookie?.contains("_UP_X=SESS1") == true)
        val req = server.takeRequest()
        assertTrue(req.path.orEmpty().contains("/account/info?st=ST-1&lw=scan"))
        assertTrue(req.getHeader("Cookie").orEmpty().contains("_UP_X=SESS1"))
        assertEquals("https://pan.quark.cn/", req.getHeader("Referer"))
    }

    // ---------------- 百度 ----------------

    @Test
    fun baiduQrFetch() {
        server.enqueue(
            json(
                """{"imgurl":"passport.baidu.com/v2/api/qrcode?sign=sg1&lp=pc&qrloginfrom=pc","errno":0,"sign":"sg1"}""",
            ),
        )
        val qr = PanQrLogin.fetchBaiduQr(client, base)
        assertEquals("sg1", qr?.sign)
        assertEquals("https://passport.baidu.com/v2/api/qrcode?sign=sg1&lp=pc&qrloginfrom=pc", qr?.imageUrl)
    }

    @Test
    fun baiduUnicastPoll() {
        server.enqueue(json("""jQuery123_456({"errno":0,"v":"bduss=BD1&stoken=ST1"})"""))
        val login = PanQrLogin.pollBaiduUnicast(client, base, "sg1")
        assertEquals("BD1", login?.bduss)
        assertEquals("ST1", login?.stoken)
    }

    @Test
    fun baiduVEncoded() {
        val login = PanQrLogin.parseBaiduV("bduss%3DBD1%26stoken%3DST1")
        assertEquals("BD1", login?.bduss)
        assertEquals("ST1", login?.stoken)
    }

    // ---------------- 阿里 ----------------

    @Test
    fun aliyunQrFetch() {
        server.enqueue(json("""{"qrCodeUrl":"https://qr.example.com/abc"}"""))
        val qr = PanQrLogin.fetchAliyunQr(client, base, "ID", "SECRET")
        assertEquals("https://qr.example.com/abc", qr?.qrCodeUrl)
        val req = server.takeRequest()
        assertTrue(req.path.orEmpty().endsWith("/oauth/authorize/qrcode"))
        assertTrue(req.body.readUtf8().contains("\"client_id\":\"ID\""))
    }

    @Test
    fun aliyunPollStates() {
        val qrUrl = "$base/qr/abc"
        server.enqueue(json("""{"status":"WaitLogin"}"""))
        assertEquals(PanQrLogin.AliyunStatus.WAIT, PanQrLogin.pollAliyunStatus(client, qrUrl).status)
        server.enqueue(json("""{"status":"ScanSuccess"}"""))
        assertEquals(PanQrLogin.AliyunStatus.SCANNED, PanQrLogin.pollAliyunStatus(client, qrUrl).status)
        server.enqueue(json("""{"status":"LoginSuccess","authCode":"AC1"}"""))
        val ok = PanQrLogin.pollAliyunStatus(client, qrUrl)
        assertEquals(PanQrLogin.AliyunStatus.SUCCESS, ok.status)
        assertEquals("AC1", ok.authCode)
        server.enqueue(json("""{"status":"QRCodeExpired"}"""))
        assertEquals(PanQrLogin.AliyunStatus.EXPIRED, PanQrLogin.pollAliyunStatus(client, qrUrl).status)
    }

    @Test
    fun aliyunExchangeCode() {
        server.enqueue(
            json("""{"access_token":"AT1","refresh_token":"RT1","expires_in":7200,"token_type":"Bearer"}"""),
        )
        val tokens = PanQrLogin.exchangeAliyunCode(client, base, "ID", "SECRET", "AC1")
        assertEquals("AT1", tokens?.accessToken)
        assertEquals("RT1", tokens?.refreshToken)
        assertEquals(7200L, tokens?.expiresIn)
    }

    // ---------------- 会话 ----------------

    @Test
    fun guangyaSessionIsManualOnly() = runBlocking {
        val session = PanQrSession(
            provider = PanProvider.GUANGYA,
            scope = CoroutineScope(Dispatchers.Default),
            client = client,
            aliyunClientId = "",
            aliyunClientSecret = "",
        )
        session.start()
        delay(500)
        val state = session.state.value
        assertTrue(state is QrState.Error)
        assertTrue((state as QrState.Error).message.contains("手动输入"))
        session.cancel()
    }

    @Test
    fun aliyunSessionWithoutCredentials() = runBlocking {
        val session = PanQrSession(
            provider = PanProvider.ALIYUN,
            scope = CoroutineScope(Dispatchers.Default),
            client = client,
            aliyunClientId = "",
            aliyunClientSecret = "",
        )
        session.start()
        delay(500)
        val state = session.state.value
        assertTrue(state is QrState.Error)
        assertTrue((state as QrState.Error).message.contains("凭证未配置"))
        session.cancel()
    }
}
