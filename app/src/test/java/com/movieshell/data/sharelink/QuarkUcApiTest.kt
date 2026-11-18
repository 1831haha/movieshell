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
    fun playRefererFor_previewUcHostUsesDriveUc() {
        // 2026-11-18 沙箱实测：预览 m3u8 在 drive.uc.cn 主机上，Referer 配
        // pan.quark.cn 回 403，配 https://drive.uc.cn/ 回 200。
        assertEquals(
            "https://drive.uc.cn/",
            QuarkUcApi.playRefererFor(
                "https://video-play-h-share-preview-zb.drive.uc.cn/share/video/preview/qv/x/media.m3u8",
                cfg,
            ),
        )
    }

    @Test
    fun playRefererFor_transcodeQuarkHostUsesDriveQuark() {
        assertEquals(
            "https://drive.quark.cn/",
            QuarkUcApi.playRefererFor(
                "https://video-play-h-zb.drive.quark.cn/qv/x/db4aa08.m3u8",
                cfg,
            ),
        )
    }

    @Test
    fun playRefererFor_downloadHostKeepsConfigReferer() {
        // 直链（dl-*）仍用配置 Referer，不动已验通过的口径。
        assertEquals(
            "https://pan.quark.cn/",
            QuarkUcApi.playRefererFor("https://dl-guest-zb-u.drive.quark.cn/xxx/01.mp4", cfg),
        )
    }

    @Test
    fun playRefererFor_unknownHostKeepsConfigReferer() {
        assertEquals(
            "https://pan.quark.cn/",
            QuarkUcApi.playRefererFor("https://example.com/x.m3u8", cfg),
        )
    }

    @Test
    fun playbackCookie_apiPugsWinsOverSessionPugs() {
        // 响应下发的 __pugs 优先，会话里的旧 __pugs 不盖它，其余会话 Cookie 保留。
        assertEquals(
            "__pugs=new123; __puus=aaa; _UP_A=bbb",
            QuarkUcApi.playbackCookie("new123", "__puus=aaa; __pugs=old456; _UP_A=bbb"),
        )
    }

    @Test
    fun playbackCookie_fallsBackToSessionPugs() {
        // 响应没下发 __pugs（file/v2/play 未必下发）时，取登录 Cookie 里的。
        assertEquals(
            "__pugs=sess789; __puus=aaa",
            QuarkUcApi.playbackCookie("", "__puus=aaa; __pugs=sess789"),
        )
    }

    @Test
    fun playbackCookie_noPugsSendsWholeSessionCookie() {
        // 两边都没有 __pugs 时整串会话 Cookie 一并带上，会话绑定也满足。
        assertEquals(
            "__puus=aaa; _UP_A=bbb",
            QuarkUcApi.playbackCookie("", "__puus=aaa; _UP_A=bbb"),
        )
    }

    @Test
    fun playbackCookie_bothEmptyReturnsEmpty() {
        assertEquals("", QuarkUcApi.playbackCookie("", ""))
        assertEquals("", QuarkUcApi.playbackCookie("  ", " ; "))
    }

    @Test
    fun keepPugs_keepsTranscodeCookieWhenPresent() {
        val out = QuarkUcApi.SaveOutcome("https://video-play.example.com/x.m3u8", "newpugs", "fid1", "")
        assertEquals("newpugs", QuarkUcApi.keepPugs(out, "oldpugs").cookie)
    }

    @Test
    fun keepPugs_fillsBlankFromDownloadStep() {
        val out = QuarkUcApi.SaveOutcome("https://video-play.example.com/x.m3u8", "", "fid1", "")
        val kept = QuarkUcApi.keepPugs(out, "oldpugs")
        assertEquals("oldpugs", kept.cookie)
        assertEquals("https://video-play.example.com/x.m3u8", kept.url)
    }

    @Test
    fun keepPugs_bothBlankStaysBlank() {
        val out = QuarkUcApi.SaveOutcome("https://video-play.example.com/x.m3u8", "", "fid1", "")
        assertEquals("", QuarkUcApi.keepPugs(out, "").cookie)
    }

    @Test
    fun saveFlow_downloadFailsButKeepsFreshPugs() {
        // 回归 1.3.14：直取拿不到 URL、但 file/download 响应下了新鲜 __pugs 时，
        // pugs 必须留在 SaveOutcome.cookie 里给后面的转码用，不能丢。
        server.enqueue(json("""{"code":0,"data":{"list":[{"fid":"tvdir","file_name":"TV"}]}}"""))
        server.enqueue(json("""{"code":0,"data":{"list":[]}}"""))
        server.enqueue(json("""{"code":0,"data":{"list":[]}}"""))
        server.enqueue(json("""{"code":0,"data":{"task_id":"task1"}}"""))
        server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":[]}}}"""))
        server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":["saved1"]}}}"""))
        server.enqueue(
            MockResponse()
                .setBody("""{"code":0,"data":[]}""")
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Set-Cookie", "__pugs=fresh999; Path=/"),
        )

        val outcome = QuarkUcApi.saveFlow(
            ShareHttp.defaultClient(), cfg,
            ShareSession.QuarkUc("pwd1", "st1", ""),
            ShareFile(fid = "f1", name = "01.mp4", size = 123456L, fidToken = "tok1"),
            "cookie123",
        ) { /* 不睡，快进 */ }

        assertEquals("", outcome.url)
        assertEquals("fresh999", outcome.cookie)
        assertEquals("saved1", outcome.savedFid)
        assertEquals("download_failed", outcome.error)
    }

    @Test
    fun pollTask_returnsFidAfterFourEmptyPolls() {
        // 旧轮询 3 次×1 秒到这里就超时返回 null；新轮询 30 次×2 秒必须等到第 5 次。
        repeat(4) {
            server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":[]}}}"""))
        }
        server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":["saved9"]}}}"""))

        // v1.3.12 修：pollTask 的 sleep 不是尾参（后面还有 maxPolls），尾随
        // lambda 会绑到 maxPolls(Int) 上，sleep 拿不到值——必须具名传。
        val fid = QuarkUcApi.pollTask(ShareHttp.defaultClient(), cfg, "", "task9", sleep = { /* 不睡，快进 */ })

        assertEquals("saved9", fid)
    }

    @Test
    fun videoPreview_returnsPugsFromSetCookie() {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"code":0,"data":{"play_info":{"url":"https://video-play-h-share-preview-zb.drive.uc.cn/share/video/preview/qv/x/media.m3u8"}}}""",
                )
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setHeader("Set-Cookie", "__pugs=PUGS123; Path=/"),
        )

        val (url, pugs) = QuarkUcApi.videoPreview(
            ShareHttp.defaultClient(), cfg,
            ShareSession.QuarkUc("pwd1", "st1", ""),
            ShareFile(fid = "f1", name = "01.mp4", size = 1L, fidToken = "tok1"),
        )

        assertTrue(url.contains("video-play-h-share-preview-zb.drive.uc.cn"))
        assertEquals("PUGS123", pugs)
    }

    /** 转存整链的 mock 排队：根目录有 TV → TV 为空 → 保存 → 轮询两次出 fid → 直链。 */
    private fun enqueueSaveFlowChain(downloadDataJson: String) {
        server.enqueue(json("""{"code":0,"data":{"list":[{"fid":"tvdir","file_name":"TV"}]}}"""))
        server.enqueue(json("""{"code":0,"data":{"list":[]}}"""))
        server.enqueue(json("""{"code":0,"data":{"list":[]}}"""))
        server.enqueue(json("""{"code":0,"data":{"task_id":"task1"}}"""))
        server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":[]}}}"""))
        server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":["saved1"]}}}"""))
        server.enqueue(json("""{"code":0,"data":$downloadDataJson}"""))
    }

    @Test
    fun saveFlow_objectArrayDownloadUrlReturnsDirectLink() {
        // 回归 1.3.8：自有盘 file/download 的 data 是对象数组时，之前只按
        // 字符串数组取，拿到的 URL 是空的，直取失败只能走转码。
        enqueueSaveFlowChain("""[{"download_url":"https://dl.example.com/01.mp4"}]""")

        val outcome = QuarkUcApi.saveFlow(
            ShareHttp.defaultClient(), cfg,
            ShareSession.QuarkUc("pwd1", "st1", ""),
            ShareFile(fid = "f1", name = "01.mp4", size = 123456L, fidToken = "tok1"),
            "cookie123",
        ) { /* 不睡，快进 */ }

        assertEquals("https://dl.example.com/01.mp4", outcome.url)
        assertEquals("saved1", outcome.savedFid)
    }

    @Test
    fun saveFlow_stringArrayDownloadUrlReturnsDirectLink() {
        // 字符串数组形态是旧口径，改完不能丢。
        enqueueSaveFlowChain("""["https://dl.example.com/02.mp4"]""")

        val outcome = QuarkUcApi.saveFlow(
            ShareHttp.defaultClient(), cfg,
            ShareSession.QuarkUc("pwd1", "st1", ""),
            ShareFile(fid = "f1", name = "02.mp4", size = 123456L, fidToken = "tok1"),
            "cookie123",
        ) { /* 不睡，快进 */ }

        assertEquals("https://dl.example.com/02.mp4", outcome.url)
        assertEquals("saved1", outcome.savedFid)
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
