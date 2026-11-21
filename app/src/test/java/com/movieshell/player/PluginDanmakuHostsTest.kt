package com.movieshell.player

import com.movieshell.player.danmaku.DANMU_SERVICE_MIN_VERSION
import com.movieshell.player.danmaku.PLUGIN_BUILTIN_DANMAKU_HOSTS
import com.movieshell.player.danmaku.isDanmakuPluginVersionSupported
import com.movieshell.player.danmaku.serializePluginDanmakuHosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.8 A5：插件弹幕源——写 danmu-service env 的 host 序列化与版本门（纯函数）。 */
class PluginDanmakuHostsTest {

    @Test fun `serialize joins with commas and trims whitespace`() {
        assertEquals(
            "a.example.com,b.example.com",
            serializePluginDanmakuHosts("  a.example.com \n b.example.com "),
        )
    }

    @Test fun `serialize dedups in order`() {
        assertEquals(
            "a.example.com,b.example.com",
            serializePluginDanmakuHosts("a.example.com\nb.example.com\na.example.com"),
        )
    }

    @Test fun `serialize drops hosts already in plugin builtin fourteen`() {
        assertEquals(14, PLUGIN_BUILTIN_DANMAKU_HOSTS.size)
        assertEquals(
            "a.example.com",
            serializePluginDanmakuHosts("dm.999.team\na.example.com\ndanmu.cynn.top"),
        )
    }

    @Test fun `serialize drops builtin hosts before truncating so custom ones keep their slots`() {
        val raw = (PLUGIN_BUILTIN_DANMAKU_HOSTS + listOf("a.example.com", "b.example.com"))
            .joinToString("\n")
        assertEquals("a.example.com,b.example.com", serializePluginDanmakuHosts(raw))
    }

    @Test fun `serialize truncates to ten custom hosts`() {
        val raw = (1..12).joinToString("\n") { "h$it.example.com" }
        val serialized = serializePluginDanmakuHosts(raw)
        assertEquals(10, serialized.split(",").size)
        assertEquals("h1.example.com", serialized.split(",").first())
        assertEquals("h10.example.com", serialized.split(",").last())
    }

    @Test fun `serialize normalizes full urls and drops invalid lines`() {
        assertEquals(
            "a.example.com",
            serializePluginDanmakuHosts("https://a.example.com/123\nnotahost\nhttp://"),
        )
    }

    @Test fun `serialize empty in empty out`() {
        assertEquals("", serializePluginDanmakuHosts(""))
        assertEquals("", serializePluginDanmakuHosts("dm.999.team"))
    }

    @Test fun `version gate rejects 1_0_1`() {
        assertFalse(isDanmakuPluginVersionSupported("1.0.1"))
    }

    @Test fun `version gate allows 1_0_2`() {
        assertEquals("1.0.2", DANMU_SERVICE_MIN_VERSION)
        assertTrue(isDanmakuPluginVersionSupported("1.0.2"))
    }

    @Test fun `version gate compares numerically not lexicographically`() {
        assertTrue(isDanmakuPluginVersionSupported("1.0.10"))
        assertTrue(isDanmakuPluginVersionSupported("1.10.0"))
        assertTrue(isDanmakuPluginVersionSupported("2.0.0"))
    }

    @Test fun `version gate rejects null blank and malformed`() {
        assertFalse(isDanmakuPluginVersionSupported(null))
        assertFalse(isDanmakuPluginVersionSupported(""))
        assertFalse(isDanmakuPluginVersionSupported("1.0"))
        assertFalse(isDanmakuPluginVersionSupported("abc"))
    }
}
