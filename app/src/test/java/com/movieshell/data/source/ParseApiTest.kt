package com.movieshell.data.source

import com.movieshell.data.model.ParseConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * ParseApi 单元测试（MockWebServer）。
 * type=0（WebView 嗅探）依赖 Android WebView，只能在真机/模拟器验证，不进单元测试。
 * Type4ApiTest 的 8 个用例保持原样，不动。
 */
class ParseApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ParseApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ParseApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun type1(
        path: String = "/parse1.php",
        header: Map<String, String> = emptyMap(),
    ) = ParseConfig(name = "t1", url = server.url(path).toString(), type = 1, header = header)

    private fun json(body: String) = MockResponse()
        .setBody(body)
        .setHeader("Content-Type", "application/json; charset=utf-8")

    @Test
    fun type1_success_returnsDirectUrl() = runTest {
        server.enqueue(json("""{"url":"https://cdn.example/x.m3u8"}"""))

        val result = api.parse(listOf(type1()), "https://v.example/vid")

        assertEquals("https://cdn.example/x.m3u8", result)
        // 请求是 GET parse.url + urlencode(videoUrl)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(req.path!!.startsWith("/parse1.php"))
        assertTrue(req.path!!.contains("https%3A%2F%2F"))
    }

    @Test
    fun type1_dataEnvelope_returnsNestedUrl() = runTest {
        server.enqueue(json("""{"data":{"url":"https://cdn.example/y.mp4"}}"""))

        val result = api.parse(listOf(type1()), "vid123")

        assertEquals("https://cdn.example/y.mp4", result)
    }

    @Test
    fun type1_noUrlField_fallsThroughToNext() = runTest {
        // 第一条返回无 url 字段 -> 换下一条
        server.enqueue(json("""{"msg":"not found"}"""))
        server.enqueue(json("""{"url":"https://cdn.example/z.m3u8"}"""))

        val result = api.parse(listOf(type1("/a.php"), type1("/b.php")), "vid123")

        assertEquals("https://cdn.example/z.m3u8", result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun type1_httpError_fallsThroughToNext() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(json("""{"url":"https://cdn.example/ok.m3u8"}"""))

        val result = api.parse(listOf(type1("/a.php"), type1("/b.php")), "vid123")

        assertEquals("https://cdn.example/ok.m3u8", result)
    }

    @Test
    fun type1_sendsParseHeader() = runTest {
        server.enqueue(json("""{"url":"https://cdn.example/x.m3u8"}"""))

        val cfg = type1(header = mapOf("User-Agent" to "UA-TEST/1.0"))
        val result = api.parse(listOf(cfg), "vid123")

        assertEquals("https://cdn.example/x.m3u8", result)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("UA-TEST/1.0", req.getHeader("User-Agent"))
    }

    @Test
    fun emptyParses_returnsEmpty() = runTest {
        assertEquals("", api.parse(emptyList(), "vid123"))
        assertEquals("", api.parse(listOf(type1()), ""))
        assertEquals(0, server.requestCount)
    }

    // ---------------- extractUrl 纯函数 ----------------

    @Test
    fun extractUrl_handlesBothShapes() {
        assertEquals("https://a/b.m3u8", api.extractUrl("""{"url":"https://a/b.m3u8"}"""))
        assertEquals("https://a/c.m3u8", api.extractUrl("""{"data":{"url":"https://a/c.m3u8"}}"""))
        assertEquals("", api.extractUrl("""{"msg":"no"}"""))
        assertEquals("", api.extractUrl("not json"))
    }
}
