package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** v1.4.8 A3：EPG 匹配分层（归一化精确 + 单向包含，第三层已删）与地址解析。 */
class EpgFetcherTest {

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ms))

    /** 每台一个当前节目 + 一个下一档；display-name 列表按条目逐个比。 */
    private fun xmlFor(channels: List<Triple<String, List<String>, String>>): String {
        val now = System.currentTimeMillis()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
            for ((id, names, _) in channels) {
                append("<channel id=\"").append(id).append("\">")
                for (n in names) append("<display-name>").append(n).append("</display-name>")
                append("</channel>")
            }
            for ((id, _, title) in channels) {
                append("<programme start=\"").append(fmt(now - 30 * 60 * 1000))
                    .append("\" stop=\"").append(fmt(now + 30 * 60 * 1000))
                    .append("\" channel=\"").append(id).append("\"><title>")
                    .append(title).append("</title></programme>")
                append("<programme start=\"").append(fmt(now + 30 * 60 * 1000))
                    .append("\" stop=\"").append(fmt(now + 90 * 60 * 1000))
                    .append("\" channel=\"").append(id).append("\"><title>")
                    .append(title).append("-下一档</title></programme>")
            }
            append("</tv>")
        }
    }

    @Test
    fun normalizeEqual() {
        assertTrue(EpgFetcher.epgNameMatches("湖南卫视 HD", "湖南卫视"))
        assertTrue(EpgFetcher.epgNameMatches("湖南卫视", "湖南卫视高清"))
        assertTrue(EpgFetcher.epgNameMatches("湖南卫视超清", "湖南卫视"))
        assertTrue(EpgFetcher.epgNameMatches("CCTV-1", "cctv1"))
        assertTrue(EpgFetcher.epgNameMatches("CCTV-1 高清", "CCTV-1"))
        assertEquals("cctv1", EpgFetcher.normalizeChannelName("CCTV-1"))
        assertEquals("湖南卫视", EpgFetcher.normalizeChannelName("湖南卫视 HD"))
    }

    @Test
    fun cctvValidationTable() {
        val cases = listOf(
            Triple("CCTV", "CCTV-1", true),
            Triple("CCTV", "CCTV-5", true),
            Triple("CCTV", "CCTV-新闻", false),
            Triple("CCTV", "CCTV-体育", false),
            Triple("CCTV", "湖南卫视", false),
            Triple("卫视", "CCTV-1", false),
            Triple("卫视", "CCTV-5", false),
            Triple("卫视", "CCTV-新闻", false),
            Triple("卫视", "CCTV-体育", false),
            Triple("卫视", "湖南卫视", false),
        )
        for ((epg, channel, expected) in cases) {
            assertEquals(
                "EPG=$epg 频道=$channel",
                expected,
                EpgFetcher.epgNameMatches(epg, channel),
            )
        }
    }

    @Test
    fun namingDifferenceReturnsNull() {
        val xml = xmlFor(listOf(Triple("cctv1", listOf("央视一套"), "新闻联播")))
        assertEquals(null to null, EpgFetcher.parse(xml, "CCTV-1"))
        val xml2 = xmlFor(listOf(Triple("zj", listOf("浙江卫视"), "中国好声音")))
        assertEquals(null to null, EpgFetcher.parse(xml2, "湖南卫视"))
    }

    @Test
    fun multiDisplayNameMatchedPerName() {
        // 拼串旧口径下 "CCTV 新闻" 会整体含 "CCTV-新闻" 而误命中；
        // 逐个比后 "CCTV" 与 "新闻综合" 单独都不命中，返回 null。
        val xml = xmlFor(listOf(Triple("c1", listOf("CCTV", "新闻综合"), "新闻联播")))
        assertEquals(null to null, EpgFetcher.parse(xml, "CCTV-新闻"))
        // 任一名字命中即该 channel id 命中。
        val xml2 = xmlFor(listOf(Triple("c1", listOf("CCTV-1", "CCTV1 综合"), "新闻联播")))
        assertEquals("新闻联播", EpgFetcher.parse(xml2, "CCTV-1").first?.title)
        assertEquals("新闻联播-下一档", EpgFetcher.parse(xml2, "CCTV-1").second?.title)
    }

    @Test
    fun multiChannelMultiDisplayName() {
        val xml = xmlFor(
            listOf(
                Triple("cctv1", listOf("CCTV-1", "CCTV 1"), "新闻联播"),
                Triple("hunan", listOf("湖南卫视", "Hunan TV"), "快乐大本营"),
            ),
        )
        val cctv = EpgFetcher.parse(xml, "CCTV-1")
        assertEquals("新闻联播", cctv.first?.title)
        assertEquals("新闻联播-下一档", cctv.second?.title)
        val hunan = EpgFetcher.parse(xml, "湖南卫视")
        assertEquals("快乐大本营", hunan.first?.title)
        assertEquals("快乐大本营-下一档", hunan.second?.title)
        // 英文 display-name 只命中自己的台。
        assertEquals("快乐大本营", EpgFetcher.parse(xml, "Hunan TV").first?.title)
        assertEquals(null to null, EpgFetcher.parse(xml, "CCTV-2"))
    }

    @Test
    fun noCrossSourceMergeAtParseLevel() {
        // 同一 channel id 在两个源里各有节目：每次 parse 只取当次 XML 的结果，不合并。
        val xmlA = xmlFor(listOf(Triple("cctv1", listOf("CCTV-1"), "源A节目")))
        val xmlB = xmlFor(listOf(Triple("cctv1", listOf("CCTV-1"), "源B节目")))
        assertEquals("源A节目", EpgFetcher.parse(xmlA, "CCTV-1").first?.title)
        assertEquals("源B节目", EpgFetcher.parse(xmlB, "CCTV-1").first?.title)
    }

    @Test
    fun resolveEpgUrl() {
        assertEquals(
            "https://a.example.com/epg.xml",
            EpgFetcher.resolveEpgUrl("https://a.example.com/epg.xml", "https://g.example.com/epg.xml"),
        )
        assertEquals(
            "https://g.example.com/epg.xml",
            EpgFetcher.resolveEpgUrl("", "https://g.example.com/epg.xml"),
        )
        assertEquals(
            "https://g.example.com/epg.xml",
            EpgFetcher.resolveEpgUrl("   ", "https://g.example.com/epg.xml"),
        )
        assertEquals(
            "https://g.example.com/epg.xml",
            EpgFetcher.resolveEpgUrl(null, "https://g.example.com/epg.xml"),
        )
        assertEquals("", EpgFetcher.resolveEpgUrl("", ""))
        // 本源已配但拉取失败/匹配不到时，调用方返回 (null, null)，不回落全局——
        // 锁定解析层只认“本源空才用全局”这一条。
        assertEquals(
            "https://a.example.com/epg.xml",
            EpgFetcher.resolveEpgUrl("https://a.example.com/epg.xml", "https://g.example.com/epg.xml"),
        )
    }

    @Test
    fun emptyChannelNameReturnsNull() {
        val xml = xmlFor(listOf(Triple("cctv1", listOf("CCTV-1"), "新闻联播")))
        assertEquals(null to null, EpgFetcher.parse(xml, ""))
        assertEquals(null to null, EpgFetcher.parse(xml, "   "))
        assertFalse(EpgFetcher.epgNameMatches("", "CCTV-1"))
        assertFalse(EpgFetcher.epgNameMatches("CCTV", ""))
    }
}
