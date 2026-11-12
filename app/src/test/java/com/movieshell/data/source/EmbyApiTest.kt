package com.movieshell.data.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
