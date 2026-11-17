package com.movieshell.data.sharelink

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** v1.3.5：夸克/UC 分享接口（MockWebServer；真接口用 drive-pc.quark.cn 实测过）。 */
class QuarkUcApiTest {

    private lateinit var server: MockWebServer
    private lateinit var cfg: QuarkUcApi.Config

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cfg = QuarkUcApi.Config(
            apiBase = server.url("/1/clouddrive").toString().trimEnd('/'),
            pr = "pr=ucpro&fr=pc",
            ua = "test-ua",
            referer = "https://pan.quark.cn/",
            fidsAsList = false,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse()
        .setBody(body)
        .setHeader("Content-Type", "application/json; charset=utf-8")

    @Test
    fun fetchToken_successReturnsStokenAndTitle() {
        server.enqueue(json("""{"code":0,"data":{"stoken":"ST123","title":"G gǔ yǔ"}}"""))

        val (stoken, title) = QuarkUcApi.fetchToken(ShareHttp.defaultClient(), cfg, "94920962092b", "")

        assertEquals("ST123", stoken)
        assertEquals("G gǔ yǔ", title)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(req.path!!.contains("/share/sharepage/token"))
        assertTrue(req.path!!.contains("pr=ucpro"))
    }

    @Test
    fun fetchToken_deadShareReturnsEmpty() {
        server.enqueue(json("""{"code":41027,"message":"share not found"}"""))

        val (stoken, title) = QuarkUcApi.fetchToken(ShareHttp.defaultClient(), cfg, "deadid", "")

        assertEquals("", stoken)
        assertEquals("", title)
    }

    @Test
    fun detailPage_parsesFilesAndFidToken() {
        server.enqueue(
            json(
                """{"code":0,"data":{"list":[
                    {"fid":"f1","file_name":"01.mp4","size":123456,"dir":false,"share_fid_token":"tok1","duration":2725},
                    {"fid":"d1","file_name":"4k","size":0,"dir":true}
                ]}}""",
            ),
        )

        val files = QuarkUcApi.detailPage(ShareHttp.defaultClient(), cfg, "94920962092b", "ST123", "0", 1)

        assertEquals(2, files.size)
        val video = files[0]
        assertEquals("f1", video.fid)
        assertEquals("01.mp4", video.name)
        assertEquals(123456L, video.size)
        assertTrue(!video.isDir)
        assertEquals("tok1", video.fidToken)
        assertEquals(2725L, video.durationSec)
        assertTrue(files[1].isDir)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(req.path!!.contains("ver=2"))
        assertTrue(req.path!!.contains("pdir_fid=0"))
    }

    @Test
    fun detailPage_errorCodeReturnsEmpty() {
        server.enqueue(json("""{"code":41027,"message":"gone"}"""))

        val files = QuarkUcApi.detailPage(ShareHttp.defaultClient(), cfg, "deadid", "ST", "0", 1)

        assertTrue(files.isEmpty())
    }

    @Test
    fun check_unparseableUrlReturnsUnknownWithoutNetwork() {
        assertEquals(
            QuarkUcApi.Check.UNKNOWN,
            QuarkUcApi.check(SharePlatform.QUARK, "https://example.com/not-a-share"),
        )
    }
}
