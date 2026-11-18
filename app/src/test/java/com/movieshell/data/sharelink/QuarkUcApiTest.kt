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

    @Test
    fun shareHttpEnc_encodesPlusSlashEqualsAndSpace() {
        // 假 token：含 +、/、=，服务端收到裸值会被截断；空格编 %20 不是 +。
        assertEquals("Ab%2BC%2FdEf%3D12", ShareHttp.enc("Ab+C/dEf=12"))
        assertEquals("a%20b", ShareHttp.enc("a b"))
        assertEquals("abcdef1234", ShareHttp.enc("abcdef1234"))
    }

    @Test
    fun detailPage_encodesSpecialCharStokenInQuery() {
        server.enqueue(
            json(
                """{"code":0,"data":{"list":[
                    {"fid":"d1","file_name":"G gǔ yǔ","size":0,"dir":true}
                ]}}""",
            ),
        )

        // 假 stoken：含 +、/、=，不编码服务端回 14001、目录报空。
        val files = QuarkUcApi.detailPage(
            ShareHttp.defaultClient(), cfg, "abcdef1234", "Ab+C/dEf=12", "0", 1,
        )

        assertEquals(1, files.size)
        assertTrue(files[0].isDir)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        val path = req.path!!
        assertTrue(path.contains("stoken=Ab%2BC%2FdEf%3D12"))
        assertTrue(!path.contains("stoken=Ab+C/dEf=12"))
    }

    @Test
    fun listDir_specialCharStokenReturnsFolderNotEmpty() {
        // token 回假 stoken（含 +、/、=），detail 回一个文件夹：回归 1.3.6，
        // 不编码时服务端报 14001、listDir 报“这个分享里是空的”。
        server.enqueue(json("""{"code":0,"data":{"stoken":"Ab+C/dEf=12","title":"G gǔ yǔ"}}"""))
        server.enqueue(
            json(
                """{"code":0,"data":{"list":[
                    {"fid":"d1","file_name":"G gǔ yǔ","size":0,"dir":true}
                ]}}""",
            ),
        )

        val result = QuarkUcApi.listDir(
            SharePlatform.QUARK, "https://pan.quark.cn/s/abcdef1234", "0",
            ShareHttp.defaultClient(), cfg,
        )

        assertTrue(result is ShareResult.Ok)
        val listing = (result as ShareResult.Ok).value
        assertEquals(1, listing.files.size)
        assertEquals("G gǔ yǔ", listing.files[0].name)
        assertTrue(listing.files[0].isDir)
        // detail 那次请求里的 stoken 必须是编码后的。
        server.takeRequest(5, TimeUnit.SECONDS)!!
        val detailReq = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(detailReq.path!!.contains("stoken=Ab%2BC%2FdEf%3D12"))
    }
}
