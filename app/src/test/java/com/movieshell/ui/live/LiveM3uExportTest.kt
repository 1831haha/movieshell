package com.movieshell.ui.live

import com.movieshell.data.store.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.33：直播 M3U 导出拼文本（标准字段，不发明自定义字段、不带收藏状态）。 */
class LiveM3uExportTest {

    private val channels = listOf(
        LiveChannel(
            id = "m3u:央视|http://a.example.com/cctv1.m3u8",
            name = "CCTV-1",
            logo = "http://a.example.com/logo.png",
            url = "http://a.example.com/cctv1.m3u8",
            group = "央视",
            favorite = true,
        ),
        LiveChannel(
            id = "manual|http://b.example.com/hunan.m3u8",
            name = "湖南卫视",
            url = "http://b.example.com/hunan.m3u8",
            group = "卫视",
            manual = true,
        ),
    )

    @Test
    fun buildM3uExport_fullText() {
        val text = buildM3uExport(channels, "http://epg.example.com/epg.xml")
        val expected = "#EXTM3U x-tvg-url=\"http://epg.example.com/epg.xml\"\n" +
            "#EXTINF:-1 tvg-id=\"m3u:央视|http://a.example.com/cctv1.m3u8\" tvg-name=\"CCTV-1\" tvg-logo=\"http://a.example.com/logo.png\" group-title=\"央视\",CCTV-1\n" +
            "http://a.example.com/cctv1.m3u8\n" +
            "#EXTINF:-1 tvg-id=\"manual|http://b.example.com/hunan.m3u8\" tvg-name=\"湖南卫视\" group-title=\"卫视\",湖南卫视\n" +
            "http://b.example.com/hunan.m3u8\n"
        assertEquals(expected, text)
    }

    @Test
    fun buildM3uExport_noGlobalEpgAndSkipsBlankUrl() {
        val list = channels + LiveChannel(id = "x", name = "空地址台", url = "")
        val text = buildM3uExport(list, "")
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertFalse(text.contains("空地址台"))
        // 收藏状态不进标准 M3U 字段。
        assertFalse(text.contains("favorite"))
    }

    @Test
    fun buildM3uExport_escapesQuotesInAttributes() {
        val ch = LiveChannel(id = "s|u", name = "台\"名", url = "http://x/y.m3u8", group = "分\"组")
        val text = buildM3uExport(listOf(ch), "")
        assertTrue(text.contains("tvg-name=\"台'名\""))
        assertTrue(text.contains("group-title=\"分'组\""))
    }
}
