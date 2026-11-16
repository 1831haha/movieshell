package com.movieshell.data.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.1.31：Emby / Jellyfin 客户端纯函数测试（地址归一化、直链拼装、tick 换算、DTO 解析）。 */
class EmbyApiTest {

    private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `normalizeBase strips spaces slashes and emby suffix`() {
        assertEquals("http://192.168.1.10:8096", EmbyApi.normalizeBase("  http://192.168.1.10:8096/ "))
        assertEquals("http://192.168.1.10:8096", EmbyApi.normalizeBase("http://192.168.1.10:8096/emby"))
        assertEquals("http://192.168.1.10:8096", EmbyApi.normalizeBase("http://192.168.1.10:8096/emby/"))
        assertEquals("https://emby.example.com", EmbyApi.normalizeBase("https://emby.example.com"))
        // v1.1.31：裸地址补 http://
        assertEquals("http://192.168.1.10:8096", EmbyApi.normalizeBase("192.168.1.10:8096"))
        assertEquals("http://192.168.1.10:8096", EmbyApi.normalizeBase("192.168.1.10:8096/emby"))
    }

    @Test
    fun `apiBase emby has prefix jellyfin does not`() {
        assertEquals(
            "http://192.168.1.10:8096/emby",
            EmbyApi.apiBase("http://192.168.1.10:8096", EmbyApi.ServerType.EMBY),
        )
        assertEquals(
            "http://192.168.1.10:8096",
            EmbyApi.apiBase("http://192.168.1.10:8096", EmbyApi.ServerType.JELLYFIN),
        )
        assertEquals(
            "http://192.168.1.10:8096/emby",
            EmbyApi.apiBase("http://192.168.1.10:8096/emby", EmbyApi.ServerType.EMBY),
        )
    }

    @Test
    fun `ticksToMs converts 100ns ticks`() {
        assertEquals(1000L, EmbyApi.ticksToMs(10_000_000L))
        assertEquals(0L, EmbyApi.ticksToMs(0L))
        assertEquals(0L, EmbyApi.ticksToMs(-5L))
        assertEquals(90_000L, EmbyApi.ticksToMs(900_000_000L))
    }

    @Test
    fun `streamUrl is static direct play with api key`() {
        val emby = EmbyApi.streamUrl("http://192.168.1.10:8096", EmbyApi.ServerType.EMBY, "tok123", "abc")
        assertTrue(emby.contains("/emby/Videos/abc/stream"))
        assertTrue(emby.contains("Static=true"))
        assertTrue(emby.contains("api_key=tok123"))
        val jelly = EmbyApi.streamUrl("http://192.168.1.10:8096", EmbyApi.ServerType.JELLYFIN, "tok123", "abc")
        assertTrue(jelly.startsWith("http://192.168.1.10:8096/Videos/abc/stream"))
    }

    @Test
    fun `imageUrl points at primary image`() {
        val url = EmbyApi.imageUrl("http://192.168.1.10:8096", EmbyApi.ServerType.JELLYFIN, "tok123", "abc", 400)
        assertTrue(url.contains("/Items/abc/Images/Primary"))
        assertTrue(url.contains("maxWidth=400"))
    }

    @Test
    fun `items response parses with unknown fields ignored`() {
        val text = """
            {"Items":[
              {"Id":"1","Name":"示例电影","Type":"Movie","ProductionYear":2024,
               "RunTimeTicks":72000000000,"ChildCount":0,
               "UserData":{"Played":false,"PlaybackPositionTicks":3600000000},
               "SomeFutureField":{"x":1}},
              {"Id":"2","Name":"示例剧集","Type":"Series","ChildCount":12}
            ],"TotalRecordCount":2,"UnknownTop":true}
        """.trimIndent()
        val resp = jsonLenient.decodeFromString<EmbyApi.EmbyItemsResponse>(text)
        assertEquals(2, resp.Items.size)
        assertEquals("示例电影", resp.Items[0].Name)
        assertEquals(2024, resp.Items[0].ProductionYear)
        assertEquals(3_600_000_000L, resp.Items[0].UserData?.PlaybackPositionTicks)
        assertEquals(12, resp.Items[1].ChildCount)
    }

    @Test
    fun `item parses chapters when present and empty when missing`() {
        val withChapters = """
            {"Id":"9","Name":"示例电影","Type":"Movie","RunTimeTicks":6000000000,
             "Chapters":[{"Name":"Intro","StartPositionTicks":0},
                        {"Name":"Chapter 2","StartPositionTicks":900000000},
                        {"Name":"End Credits","StartPositionTicks":5400000000}]}
        """.trimIndent()
        val item = jsonLenient.decodeFromString<EmbyApi.EmbyItem>(withChapters)
        assertEquals(3, item.Chapters.size)
        assertEquals("Intro", item.Chapters[0].Name)
        assertEquals(0L, item.Chapters[0].StartPositionTicks)
        assertEquals(5_400_000_000L, item.Chapters[2].StartPositionTicks)
        val withoutChapters = jsonLenient.decodeFromString<EmbyApi.EmbyItem>(
            """{"Id":"9","Name":"示例电影","Type":"Movie"}""",
        )
        assertTrue(withoutChapters.Chapters.isEmpty())
    }

    private fun chapters(vararg pairs: Pair<String, Long>): List<EmbyApi.EmbyChapter> =
        pairs.map { EmbyApi.EmbyChapter(it.first, it.second) }

    @Test
    fun `findSkipTarget returns intro skip inside intro chapter`() {
        val target = EmbyApi.findSkipTarget(
            chapters("Intro" to 0L, "Chapter 2" to 900_000_000L, "End Credits" to 5_400_000_000L),
            positionMs = 30_000L,
            durationMs = 600_000L,
        )
        assertEquals("跳过片头", target?.label)
        assertEquals(90_000L, target?.seekToMs)
    }

    @Test
    fun `findSkipTarget returns outro skip inside credits chapter`() {
        val target = EmbyApi.findSkipTarget(
            chapters("Intro" to 0L, "Chapter 2" to 900_000_000L, "End Credits" to 5_400_000_000L),
            positionMs = 550_000L,
            durationMs = 600_000L,
        )
        assertEquals("跳过片尾", target?.label)
        assertEquals(600_000L, target?.seekToMs)
    }

    @Test
    fun `findSkipTarget returns null in middle chapter`() {
        assertNull(
            EmbyApi.findSkipTarget(
                chapters("Intro" to 0L, "Chapter 2" to 900_000_000L, "End Credits" to 5_400_000_000L),
                positionMs = 300_000L,
                durationMs = 600_000L,
            ),
        )
    }

    @Test
    fun `findSkipTarget matches chinese names and OP ED exactly`() {
        val intro = EmbyApi.findSkipTarget(
            chapters("片头" to 0L, "正片" to 600_000_000L),
            positionMs = 10_000L,
            durationMs = 600_000L,
        )
        assertEquals("跳过片头", intro?.label)
        val outro = EmbyApi.findSkipTarget(
            chapters("正片" to 0L, "ED" to 5_400_000_000L),
            positionMs = 550_000L,
            durationMs = 600_000L,
        )
        assertEquals("跳过片尾", outro?.label)
        assertEquals(600_000L, outro?.seekToMs)
    }

    @Test
    fun `splitBaseUrl splits host port and proxy path`() {
        val plain = EmbyApi.splitBaseUrl("http://192.168.1.10:8096")
        assertEquals("http", plain.scheme)
        assertEquals("192.168.1.10", plain.host)
        assertEquals("8096", plain.port)
        assertEquals("", plain.path)
        val proxy = EmbyApi.splitBaseUrl("https://emby.example.com/proxy")
        assertEquals("https", proxy.scheme)
        assertEquals("emby.example.com", proxy.host)
        assertEquals("", proxy.port)
        assertEquals("/proxy", proxy.path)
        val proxyPort = EmbyApi.splitBaseUrl("https://emby.example.com:443/a/b/")
        assertEquals("443", proxyPort.port)
        assertEquals("/a/b", proxyPort.path)
    }

    @Test
    fun `splitBaseUrl fills scheme and strips emby suffix`() {
        val bare = EmbyApi.splitBaseUrl("192.168.1.10:8096")
        assertEquals("http", bare.scheme)
        assertEquals("192.168.1.10", bare.host)
        assertEquals("8096", bare.port)
        val emby = EmbyApi.splitBaseUrl("http://192.168.1.10:8096/emby")
        assertEquals("", emby.path)
        val embyProxy = EmbyApi.splitBaseUrl("https://x.example.com/proxy/emby")
        assertEquals("/proxy", embyProxy.path)
    }

    @Test
    fun `splitBaseUrl keeps ipv6 host with brackets`() {
        val v6 = EmbyApi.splitBaseUrl("http://[::1]:8096")
        assertEquals("[::1]", v6.host)
        assertEquals("8096", v6.port)
        assertEquals("", v6.path)
    }

    @Test
    fun `joinAddress builds url and sanitizes parts`() {
        assertEquals(
            "http://192.168.1.10:8096",
            EmbyApi.joinAddress("http", "192.168.1.10", "8096", ""),
        )
        assertEquals(
            "https://emby.example.com/proxy",
            EmbyApi.joinAddress("https", "emby.example.com", "", "/proxy"),
        )
        assertEquals(
            "https://emby.example.com:443/a/b",
            EmbyApi.joinAddress("https", "emby.example.com", "443", "a/b/"),
        )
        // 非数字端口丢掉、空协议默认 http、空主机拼不出地址。
        assertEquals(
            "http://192.168.1.10",
            EmbyApi.joinAddress("", " 192.168.1.10 ", "abc", ""),
        )
        assertEquals("", EmbyApi.joinAddress("http", "  ", "8096", ""))
    }

    @Test
    fun `split and join round trip`() {
        val urls = listOf(
            "http://192.168.1.10:8096",
            "https://emby.example.com/proxy",
            "https://emby.example.com:443/a/b",
            "http://[::1]:8096",
        )
        for (url in urls) {
            val s = EmbyApi.splitBaseUrl(url)
            assertEquals(url, EmbyApi.joinAddress(s.scheme, s.host, s.port, s.path))
        }
    }

    @Test
    fun `findSkipTarget is case insensitive and ignores unknown duration`() {
        val target = EmbyApi.findSkipTarget(
            chapters("INTRODUCTION" to 0L, "Main" to 900_000_000L),
            positionMs = 5_000L,
            durationMs = 600_000L,
        )
        assertEquals("跳过片头", target?.label)
        // 最后一章是片尾但片长未知：结尾算不出，不给跳过按钮。
        assertNull(
            EmbyApi.findSkipTarget(
                chapters("Main" to 0L, "Outro" to 5_400_000_000L),
                positionMs = 550_000L,
                durationMs = 0L,
            ),
        )
        assertTrue(EmbyApi.findSkipTarget(emptyList(), 10_000L, 600_000L) == null)
    }
}
