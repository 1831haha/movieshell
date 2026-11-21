package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * v1.4.8 A2：路由 ch 参数单测（纯函数）。
 *
 * 注意：playerRoute 本体调 android.net.Uri.encode，纯 JVM 单测（无
 * Robolectric）跑不了；编码与默认值改由抽出的纯函数锁定——
 * channelIdQuerySegment（段格式 + 编码 + 空值默认）与 parseChannelIdArg
 * （读侧：空串/缺省 → null，走全局）。navArgument("ch") 的
 * defaultValue "" 与本文件 parseChannelIdArg("") == null 对应。
 */
class PlayerRouteChannelTest {

    /** 与 Uri.encode 同语义的纯编码器（只用于单测：验段格式，不验 Android API）。 */
    private val pureEncode: (String) -> String = { URLEncoder.encode(it, "UTF-8") }

    private val channelId = "m3u:央视|https://example.com/cctv1.m3u8"

    @Test
    fun `parseChannelIdArg blank or missing means no channel`() {
        assertNull(parseChannelIdArg(null))
        // navArgument("ch") 默认值 "" 落到这里就是 null = 走全局。
        assertNull(parseChannelIdArg(""))
        assertNull(parseChannelIdArg("   "))
    }

    @Test
    fun `parseChannelIdArg passes real id through`() {
        assertEquals(channelId, parseChannelIdArg(channelId))
    }

    @Test
    fun `ch segment encodes pipe and url`() {
        val segment = channelIdQuerySegment(channelId, pureEncode)
        assertTrue(segment.startsWith("&ch="))
        // `|` 与完整 URL 里的 `:`/`/` 都必须编码，否则 `&`/`?` 会破坏参数解析。
        assertFalse(segment.contains("|"))
        assertFalse(segment.contains("://"))
        assertEquals("&ch=${pureEncode(channelId)}", segment)
    }

    @Test
    fun `ch segment defaults to empty for null channelId`() {
        assertEquals("&ch=", channelIdQuerySegment(null, pureEncode))
        // 空 ch 读侧即 null，走全局（与 parseChannelIdArg("") 对应）。
        assertNull(parseChannelIdArg(""))
    }
}
