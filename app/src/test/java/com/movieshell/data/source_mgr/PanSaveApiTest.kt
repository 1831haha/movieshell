package com.movieshell.data.source_mgr

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PanSaveApi 单元测试（MockWebServer + 录制的请求断言）。
 * 覆盖转存链：建/找 TV 目录、save 取 task_id、task 轮询、取原画直链、
 * 转码选档；登录后全程真机待验，这里只保证轮询与解析逻辑。
 */
class PanSaveApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var apiBase: String

    private val pr = "pr=ucpro&fr=pc"
    private val ua = "UA"
    private val referer = "https://pan.quark.cn/"

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

    private fun save(
        fidsAsList: Boolean = false,
        sleep: (Long) -> Unit = {},
    ) = PanSaveApi.saveAndDownloadCore(
        cookie = "CK", apiBase = apiBase, pr = pr, ua = ua, referer = referer,
        pwdId = "P1", stoken = "S1", fid = "F1", fidToken = "FT1",
        fidsAsList = fidsAsList, client = client, sleep = sleep,
    )

    private fun requests(): List<okhttp3.mockwebserver.RecordedRequest> {
        val out = mutableListOf<okhttp3.mockwebserver.RecordedRequest>()
        repeat(server.requestCount) { out += server.takeRequest() }
        return out
    }

    private fun paths(reqs: List<okhttp3.mockwebserver.RecordedRequest>): List<String> =
        reqs.map { it.path.orEmpty() }

    @Test
    fun saveAndDownloadSuccess() {
        server.enqueue(json("""{"code":0,"data":{"list":[]}}"""))
        server.enqueue(json("""{"code":0,"data":{"fid":"TV123"}}"""))
        server.enqueue(json("""{"code":0,"data":{"task_id":"T1"}}"""))
        server.enqueue(json("""{"code":0,"data":{}}"""))
        server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":["NEW1"]}}}"""))
        server.enqueue(json("""{"code":0,"data":["https://dl.example.com/file.mp4"]}"""))
        val o = save()
        assertEquals("", o.error)
        assertEquals("https://dl.example.com/file.mp4", o.url)
        assertEquals("NEW1", o.savedFid)
        val reqs = requests()
        val ps = paths(reqs)
        assertEquals(6, ps.size)
        assertTrue(ps[0].contains("file/sort") && ps[0].contains("pdir_fid=0"))
        assertTrue(ps[1].contains("file?"))
        assertTrue(ps[2].contains("share/sharepage/save"))
        assertTrue(ps[3].contains("task?") && ps[3].contains("retry_index=0"))
        assertTrue(ps[4].contains("retry_index=1"))
        assertTrue(ps[5].contains("file/download"))
        // 夸克取直链时 fids 传单个字符串（UC 传单元素数组，见下个单测）。
        assertTrue(reqs[5].body.readUtf8().contains("\"fids\":\"NEW1\""))
    }

    @Test
    fun downloadFidsAsListForUc() {
        server.enqueue(json("""{"code":0,"data":{"list":[]}}"""))
        server.enqueue(json("""{"code":0,"data":{"fid":"TV123"}}"""))
        server.enqueue(json("""{"code":0,"data":{"task_id":"T1"}}"""))
        server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":["NEW1"]}}}"""))
        server.enqueue(json("""{"code":0,"data":["https://dl.example.com/file.mp4"]}"""))
        val o = save(fidsAsList = true)
        assertEquals("", o.error)
        val reqs = requests()
        assertEquals(5, reqs.size)
        assertTrue(reqs[4].body.readUtf8().contains("\"fids\":[\"NEW1\"]"))
    }

    @Test
    fun reusesExistingTvDirAndClears() {
        server.enqueue(json("""{"code":0,"data":{"list":[{"file_name":"TV","fid":"TV9","dir":true}]}}"""))
        server.enqueue(json("""{"code":0,"data":{"list":[{"fid":"OLD1"},{"fid":"OLD2"}]}}"""))
        server.enqueue(json("""{"code":0,"data":{}}"""))
        server.enqueue(json("""{"code":0,"data":{"task_id":"T2"}}"""))
        server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":["NEW2"]}}}"""))
        server.enqueue(json("""{"code":0,"data":["https://dl.example.com/f2.mp4"]}"""))
        val o = save()
        assertEquals("", o.error)
        assertEquals("NEW2", o.savedFid)
        val reqs = requests()
        val ps = paths(reqs)
        assertEquals(6, ps.size)
        assertTrue(ps[1].contains("file/sort") && ps[1].contains("pdir_fid=TV9"))
        assertTrue(ps[2].contains("file/delete"))
        val deleteBody = reqs[2].body.readUtf8()
        assertTrue(deleteBody.contains("OLD1") && deleteBody.contains("OLD2"))
    }

    @Test
    fun saveLoginExpired() {
        server.enqueue(json("""{"code":0,"data":{"list":[]}}"""))
        server.enqueue(json("""{"code":0,"data":{"fid":"TV123"}}"""))
        server.enqueue(json("""{"code":31001,"message":"require login [guest]"}"""))
        val o = save()
        assertEquals("need_login", o.error)
        assertEquals("", o.url)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun pollTimeout() {
        server.enqueue(json("""{"code":0,"data":{"list":[]}}"""))
        server.enqueue(json("""{"code":0,"data":{"fid":"TV123"}}"""))
        server.enqueue(json("""{"code":0,"data":{"task_id":"T3"}}"""))
        repeat(3) { server.enqueue(json("""{"code":0,"data":{}}""")) }
        val o = save()
        assertEquals("save_timeout", o.error)
        assertEquals("", o.savedFid)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun downloadObjectForm() {
        server.enqueue(json("""{"code":0,"data":{"list":[]}}"""))
        server.enqueue(json("""{"code":0,"data":{"fid":"TV123"}}"""))
        server.enqueue(json("""{"code":0,"data":{"task_id":"T4"}}"""))
        server.enqueue(json("""{"code":0,"data":{"save_as":{"save_as_top_fids":["NEW4"]}}}"""))
        server.enqueue(json("""{"code":0,"data":{"download_url":"https://dl.example.com/f4.mp4"}}"""))
        val o = save()
        assertEquals("", o.error)
        assertEquals("https://dl.example.com/f4.mp4", o.url)
    }

    @Test
    fun noCookieNeedsLoginWithoutRequest() {
        val o = PanSaveApi.saveAndDownloadCore(
            cookie = "", apiBase = apiBase, pr = pr, ua = ua, referer = referer,
            pwdId = "P1", stoken = "S1", fid = "F1", fidToken = "FT1",
            fidsAsList = false, client = client, sleep = {},
        )
        assertEquals("need_login", o.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun transcodePicksHighest() {
        server.enqueue(json(
            """{"code":0,"data":{"video_list":[
            {"resolution":"low","video_info":{"url":"u-low"}},
            {"resolution":"normal","video_info":{"url":"u-normal"}},
            {"resolution":"high","video_info":{"url":"u-high"}}]}}""",
        ))
        val o = PanSaveApi.transcodeCore(
            cookie = "CK", apiBase = apiBase, pr = pr, ua = ua,
            referer = referer, savedFid = "NEW1", client = client,
        )
        assertEquals("", o.error)
        assertEquals("u-high", o.url)
        assertEquals("NEW1", o.savedFid)
    }

    @Test
    fun transcodeEmptyListFails() {
        server.enqueue(json("""{"code":0,"data":{"video_list":[]}}"""))
        val o = PanSaveApi.transcodeCore(
            cookie = "CK", apiBase = apiBase, pr = pr, ua = ua,
            referer = referer, savedFid = "NEW1", client = client,
        )
        assertEquals("transcode_failed", o.error)
    }
}
