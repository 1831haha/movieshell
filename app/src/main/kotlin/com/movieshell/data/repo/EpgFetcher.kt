package com.movieshell.data.repo

import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA_SHORT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

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

    /** v0.188.0 M29：EPG 单文件下载上限 20MB，超限直接失败，不全量进内存。 */
    private const val MAX_EPG_BYTES = 20L * 1024L * 1024L

    /**
     * v0.188.0 M29：磁盘缓存目录（LiveStore.init 时注入 filesDir 下的
     * epg_cache；未注入时不缓存、只走网络，与旧行为一致）。
     * 文件名按 URL + 日期：epg_<url 哈希>_<yyyyMMdd>.xml，当天内重复查
     * 同一 EPG 直接读盘，不重复下载；写新缓存时顺手清掉同 URL 的旧日期文件。
     */
    @Volatile
    private var cacheDir: File? = null

    /** 仅 LiveStore.init 调用（同包），不要在别处乱设。 */
    internal fun setCacheDir(dir: File) {
        cacheDir = dir
    }

    // v0.217.0（F21）：SimpleDateFormat 不是线程安全的，原来是共享单例，
    // 直播页与频道详情并发查节目单时会互相干扰、个别节目时间算错或被
    // 静默过滤（外层 runCatching 把错吞成 0L）；每次新建解析器，代价可忽略。
    private fun parseTime(s: String): Long =
        runCatching {
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(s.trim())?.time ?: 0L
        }.getOrDefault(0L)

    private fun cacheFileFor(epgUrl: String): File? {
        val dir = cacheDir ?: return null
        val hash = (epgUrl.hashCode().toLong() and 0xffffffffL).toString(16)
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(System.currentTimeMillis())
        return File(dir, "epg_${hash}_$day.xml")
    }

    private fun readCache(epgUrl: String): ByteArray? {
        val f = cacheFileFor(epgUrl) ?: return null
        return runCatching { if (f.isFile) f.readBytes() else null }.getOrNull()
    }

    private fun writeCache(epgUrl: String, bytes: ByteArray) {
        val f = cacheFileFor(epgUrl) ?: return
        runCatching {
            val dir = f.parentFile ?: return
            dir.mkdirs()
            // 同 URL 的旧日期缓存清掉，目录不随天数无限长。
            val prefix = "epg_${(epgUrl.hashCode().toLong() and 0xffffffffL).toString(16)}_"
            dir.listFiles { it -> it.name.startsWith(prefix) && it != f }
                .orEmpty().forEach { runCatching { it.delete() } }
            // 二进制（可能含 gzip）不能走 AtomicFile.writeText 的字符串路径：
            // 同目录临时文件 + 改名，写一半被杀也只留无害 tmp，不污染正式缓存。
            val tmp = File(dir, "${f.name}.${UUID.randomUUID()}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    suspend fun nowAndNext(epgUrl: String, channelName: String): Pair<Program?, Program?> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 当天缓存命中直接解析，不发网络请求。
                val bytes = readCache(epgUrl)
                    ?: withTimeoutOrNull(10_000) { downloadBytes(epgUrl) }
                        ?.also { writeCache(epgUrl, it) }
                    ?: return@runCatching null to null
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

    /**
     * v0.188.0 M29：下载改分块限量读，超 20MB 即抛错，不再 body.bytes()
     * 全量进内存；外层 10 秒超时此前取消不掉阻塞中的同步 execute，
     * 现注册取消即 cancel 底层 call，超时真正起效。
     */
    private suspend fun downloadBytes(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA_SHORT)
            .build()
        val call = CmsApi.defaultClient().newCall(req)
        val job = currentCoroutineContext()[Job]
        job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val stream = resp.body?.byteStream() ?: throw IllegalStateException("空响应")
                stream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_EPG_BYTES) {
                            throw IllegalStateException("EPG 超过 20MB 上限，已放弃")
                        }
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 取消时抛回 job 自身的取消异常：超时触发的是
            // TimeoutCancellationException，withTimeoutOrNull 认它才返回 null
            // （抛普通 CancellationException 会穿出 withTimeoutOrNull）；
            // 外部取消的则是 JobCancellationException，原样透传。
            if (job?.isCancelled == true) currentCoroutineContext().ensureActive()
            throw e
        }
    }
}
