package com.movieshell.data.source

import com.movieshell.data.model.Site
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Type4Api 单元测试（MockWebServer + app/src/test/resources/type4/ 真实 fixture）。
 * fixture 来自本机 php-cli 复刻服务对"小鸭看看"规则（模式 B：独立文件）的实测。
 */
class Type4ApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: Type4Api

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Type4Api()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String): String =
        javaClass.getResource("/type4/$name.json")!!.readText()

    private fun site(apiUrl: String = server.url("/t4.php").toString()) =
        Site(key = "t4", name = "小鸭看看", type = 4, api = apiUrl)

    private fun json(body: String) = MockResponse()
        .setBody(body)
        .setHeader("Content-Type", "application/json; charset=utf-8")

    /** 按请求 path 匹配返回固定响应。 */
    private fun staticDispatcher(vararg pairs: Pair<(String) -> Boolean, MockResponse>) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return pairs.firstOrNull { (match, _) -> match(path) }?.second
                    ?: MockResponse().setResponseCode(404)
            }
        }

    // ---------------- 首页 / 分类 / 搜索 / 详情 ----------------

    @Test
    fun home_mapsTypesAndList() = runTest {
        val body = fixture("home")
        server.dispatcher = staticDispatcher({ true } to json(body))

        val (types, list) = api.parseHome(body)
        assertEquals(5, types.size)
        assertEquals("10", types[0].typeId)
        assertEquals("电影", types[0].typeName)
        assertEquals(36, list.size)
        assertEquals("bc8df8d8c2", list[0].vodId)
        assertEquals("执法交叉点", list[0].vodName)
        assertTrue(list[0].vodPic.startsWith("https://"))

        // 走 HTTP 的对外接口同样 36 条
        val viaHttp = api.home(site())
        assertEquals(36, viaHttp.size)
        assertEquals("bc8df8d8c2", viaHttp[0].vodId)
    }

    @Test
    fun category_returnsPagedList() = runTest {
        val body = fixture("category")
        server.dispatcher = staticDispatcher({ true } to json(body))

        val (types, list) = api.category(site(), "10", 1)
        assertEquals(5, types.size)
        assertTrue(list.isNotEmpty())
        assertEquals(36, list.size)

        // 分页元信息在 fixture 里（模型只返回列表，不断言模型无此字段）
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(1, root["page"]!!.jsonPrimitive.int)
        assertEquals(13, root["pagecount"]!!.jsonPrimitive.int)
        assertEquals(260, root["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun search_findsKeyword() = runTest {
        server.dispatcher = staticDispatcher({ true } to json(fixture("search")))

        val list = api.search(site(), "爱")
        assertEquals(8, list.size)
        assertEquals("所爱之人", list[0].vodName)
        assertEquals("22f0342cfa", list[0].vodId)
    }

    @Test
    fun detail_parsesVodAndEpisodes() = runTest {
        server.dispatcher = staticDispatcher({ true } to json(fixture("detail")))

        val detail = api.detail(site(), "bc8df8d8c2")
        assertEquals("bc8df8d8c2", detail.vodId)
        assertTrue(detail.vodName.isNotBlank())
        assertTrue(detail.vodPlayFrom.isNotBlank())
        assertTrue(detail.vodPlayUrl.isNotBlank())

        // 剧集切分：CmsApi.parsePlayUrl（SiteRepository.parseEpisodes 的底层实现，纯 JVM）
        val episodes = CmsApi().parsePlayUrl(detail.vodPlayUrl, detail.vodPlayFrom)
        assertTrue(episodes.isNotEmpty())
        val first = episodes[0]
        assertEquals("线路147", first.flag)
        assertEquals("第1集", first.name)
        assertTrue(first.url.isNotBlank())
        assertEquals("https://v.gsuus.com/play/e9r15mxa/index.m3u8", first.url)
    }

    // ---------------- 播放双试探 ----------------

    @Test
    fun play_modeB_fallsBackToPlayParam() = runTest {
        val homeBody = fixture("home")
        val playBody = fixture("play")
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                paths += p
                return when {
                    // 模式 B（独立文件）：?ac=play&flag=&id= 不认，落回首页分支
                    p.contains("ac=play") -> json(homeBody)
                    p.contains("play=") -> json(playBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val result = api.play(site(), "线路147", "e9r15mxa")
        assertEquals("https://v.gsuus.com/play/e9r15mxa/index.m3u8", result.url)
        assertEquals(0, result.parse)
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            result.header["User-Agent"]
        )
        // 双试探确实发生了：第一次 ?ac=play、第二次 ?play=
        assertEquals(2, paths.size)
        assertTrue(paths[0].contains("ac=play"))
        assertTrue(paths[1].contains("play="))
        assertFalse(paths[1].contains("ac="))
    }

    @Test
    fun play_modeA_hitsOnFirstTry() = runTest {
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                paths += p
                // 模式 A（BaseSpider::run）：?ac=play 直接进 playContent
                return if (p.contains("ac=play")) json(
                    """{"parse":0,"url":"https://x/y.m3u8","header":{"Referer":"https://r"}}"""
                ) else MockResponse().setResponseCode(404)
            }
        }

        val result = api.play(site(), "f", "i")
        assertEquals("https://x/y.m3u8", result.url)
        assertEquals("https://r", result.header["Referer"])
        // 一次命中，无回退
        assertEquals(1, paths.size)
        assertTrue(paths[0].contains("ac=play"))
    }

    // ---------------- URL 规范化 ----------------

    @Test
    fun normalizeApi_encodesChinesePath() {
        val url = api.normalizeApi("http://127.0.0.1:19978/人人影视 ᵈᶻ.php")!!
        assertEquals("127.0.0.1", url.host)
        assertEquals(19978, url.port)
        val path = url.encodedPath
        assertFalse(path.contains(" "))
        assertFalse(path.contains("人"))
        assertTrue(path.startsWith("/%E4%BA%BA%E4%BA%BA%E5%BD%B1%E8%A7%86%20"))
        assertTrue(path.endsWith(".php"))
    }

    // ---------------- 错误包络 ----------------

    @Test
    fun errorEnvelope_returnsEmptyWithoutThrowing() = runTest {
        val err = """{"code":500,"msg":"spider error"}"""
        server.dispatcher = staticDispatcher({ true } to json(err))

        assertTrue(api.home(site()).isEmpty())
        val (types, list) = api.category(site(), "10", 1)
        assertTrue(types.isEmpty())
        assertTrue(list.isEmpty())
        assertTrue(api.search(site(), "爱").isEmpty())
        val detail = api.detail(site(), "x")
        assertEquals("x", detail.vodId)
        assertTrue(detail.vodName.isEmpty())
        val play = api.play(site(), "f", "i")
        assertTrue(play.url.isEmpty())
        assertEquals(0, play.parse)
    }
}
