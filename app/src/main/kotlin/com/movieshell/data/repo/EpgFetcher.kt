package com.movieshell.data.repo

import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA_SHORT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * XMLTV 节目单尽力解析（v0.24.0）。
 *
 * 输入 epgUrl + 频道名：找 display-name 含频道名的 <channel>，
 * 取其 id 下的 <programme>，算出当前节目与下一个节目。
 * 任何一步失败返回 (null, null)，不崩。
 */
object EpgFetcher {

    data class Program(
        val title: String,
        val startMs: Long,
        val stopMs: Long,
        val desc: String = "",
    )

    private val timeFmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseTime(s: String): Long =
        runCatching { timeFmt.parse(s.trim())?.time ?: 0L }.getOrDefault(0L)

    suspend fun nowAndNext(epgUrl: String, channelName: String): Pair<Program?, Program?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = withTimeoutOrNull(10_000) { downloadBytes(epgUrl) } ?: return@runCatching null to null
                // v0.158.0：.xml.gz 这类压缩包尽力解压（gzip 魔数 1f 8b），
                // 普通 XML 直接按 UTF-8 解；解不开返回 (null, null)，不崩。
                val xml = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                    runCatching {
                        java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
                    }.getOrNull() ?: return@runCatching null to null
                } else {
                    bytes.toString(Charsets.UTF_8)
                }
                parse(xml, channelName.trim())
            }.getOrDefault(null to null)
        }

    private fun parse(xml: String, channelName: String): Pair<Program?, Program?> {
        if (channelName.isBlank()) return null to null
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val p = factory.newPullParser().apply { setInput(xml.reader()) }
        // channel id -> display-name
        val names = mutableMapOf<String, String>()
        var curId = ""
        var curText = StringBuilder()
        var inDisplayName = false
        data class Raw(val channel: String, val start: Long, val stop: Long, val title: String, val desc: String)
        val programmes = mutableListOf<Raw>()
        var ev = p.eventType
        var pChannel = ""
        var pStart = 0L
        var pStop = 0L
        var pTitle = StringBuilder()
        var pDesc = StringBuilder()
        var inTitle = false
        var inDesc = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> curId = p.getAttributeValue(null, "id").orEmpty()
                    "display-name" -> { inDisplayName = true; curText = StringBuilder() }
                    "programme" -> {
                        pChannel = p.getAttributeValue(null, "channel").orEmpty()
                        pStart = parseTime(p.getAttributeValue(null, "start").orEmpty())
                        pStop = parseTime(p.getAttributeValue(null, "stop").orEmpty())
                        pTitle = StringBuilder(); pDesc = StringBuilder()
                    }
                    "title" -> inTitle = true
                    "desc" -> inDesc = true
                }
                XmlPullParser.TEXT -> {
                    val t = p.text
                    if (inDisplayName) curText.append(t)
                    if (inTitle) pTitle.append(t)
                    if (inDesc) pDesc.append(t)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "display-name" -> {
                        if (curId.isNotBlank()) {
                            names[curId] = (names[curId].orEmpty() + " " + curText.toString()).trim()
                        }
                        inDisplayName = false
                    }
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "programme" -> {
                        if (pChannel.isNotBlank() && pStart > 0) {
                            programmes.add(
                                Raw(pChannel, pStart, pStop, pTitle.toString().trim(), pDesc.toString().trim()),
                            )
                        }
                    }
                }
            }
            ev = p.next()
        }
        // 频道匹配：display-name 含频道名或反之（去空白后包含）
        val norm = channelName.replace("\\s".toRegex(), "")
        val matched = names.filter { (_, n) ->
            val nn = n.replace("\\s".toRegex(), "")
            nn.contains(norm) || norm.contains(nn)
        }.keys
        if (matched.isEmpty()) return null to null
        val now = System.currentTimeMillis()
        val list = programmes.filter { it.channel in matched && it.stop > now }
            .sortedBy { it.start }
        val cur = list.firstOrNull { now >= it.start && now < it.stop }
            ?: list.firstOrNull { it.start <= now }
        val next = list.firstOrNull { it.start > now && it.title != cur?.title }
            ?: list.firstOrNull { it.start >= (cur?.stop ?: now) && it != cur }
        fun Raw.toProgram() = Program(title, start, stop, desc)
        return cur?.toProgram() to next?.toProgram()
    }

    private fun downloadBytes(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA_SHORT)
            .build()
        CmsApi.defaultClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw IllegalStateException("空响应")
        }
    }
}
