package com.movieshell.player

import com.movieshell.data.repo.LogBus
import com.movieshell.data.source.CmsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.294.7：播放前流类型识别（H6）。
 *
 * Media3 只按后缀猜类型：`/m3u8/abc`、`play.php?id=` 这类无后缀 HLS 会被当普通文件、
 * 报「无法识别格式」，只能等自动换内核兜底。这里先按 URL 判断，判断不了再用
 * HEAD / Range GET 取 Content-Type 与前几百字节（`#EXTM3U` / `<MPD`），
 * 结果记进 [hint]，PlayerManager 起播时 setMimeType，去广告 DataSource 也据此识别播放列表。
 */
object MediaTypeSniffer {

    enum class Kind { HLS, DASH, PROGRESSIVE }

    private const val TAG = "MediaType"
    private val hints = ConcurrentHashMap<String, Kind>()

    private val PROGRESSIVE_EXTS = listOf(
        ".mp4", ".mkv", ".flv", ".avi", ".webm", ".mov", ".wmv", ".mp3", ".m4a", ".aac",
        ".ts", ".m4v", ".3gp", ".ogg", ".wav", ".flac",
    )

    /** 纯函数：只看 URL。返回 null 表示看不出来。 */
    fun inferFromUrl(url: String): Kind? {
        val lower = url.trim().lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return null
        val noFrag = lower.substringBefore('#')
        val path = noFrag.substringBefore('?')
        val query = noFrag.substringAfter('?', "")
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return Kind.HLS
        if (path.endsWith(".mpd")) return Kind.DASH
        if (PROGRESSIVE_EXTS.any { path.endsWith(it) }) return Kind.PROGRESSIVE
        // /m3u8/xxx 路径段、?type=m3u8 / .m3u8 出现在 query 里（转跳型）
        if (path.split('/').any { it == "m3u8" || it == "hls" }) return Kind.HLS
        if (Regex("(^|[&=])(m3u8|hls)($|&)").containsMatchIn(query) || query.contains(".m3u8")) return Kind.HLS
        return null
    }

    /** 纯函数：Content-Type 判定。 */
    fun inferFromContentType(contentType: String?): Kind? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            ct.contains("mpegurl") -> Kind.HLS
            ct == "application/dash+xml" -> Kind.DASH
            ct.startsWith("video/") || ct.startsWith("audio/") -> Kind.PROGRESSIVE
            else -> null
        }
    }

    /** 纯函数：前几字节判定（去 BOM/空白）。 */
    fun inferFromBytes(head: ByteArray?): Kind? {
        if (head == null || head.isEmpty()) return null
        val s = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (s.startsWith("#EXTM3U")) return Kind.HLS
        if (s.startsWith("<?xml") && s.contains("<MPD") || s.startsWith("<MPD")) return Kind.DASH
        return null
    }

    fun hint(url: String): Kind? = hints[url] ?: inferFromUrl(url)

    /** 只看探测/显式登记过的结果（不按 URL 猜）。 */
    fun explicitHint(url: String): Kind? = hints[url]

    fun putHint(url: String, kind: Kind) {
        if (url.isBlank()) return
        if (hints.size > 300) hints.clear()
        hints[url] = kind
    }

    /**
     * URL 看不出类型时探测一次（≤3 秒，失败静默）。已知类型/非 http 直接返回。
     * 只在协程里调；探测异常一律当未知，不影响起播。
     */
    suspend fun ensureHint(url: String, headers: Map<String, String>): Kind? {
        hints[url]?.let { return it }
        // 地址能看出类型（含明确媒体后缀）就不探测——一次性签名地址经不起多请求一次
        inferFromUrl(url)?.let { return it }
        if (com.movieshell.data.source.SniffEngine.hasClearMediaSuffix(url)) return null
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return null
        val kind = withTimeoutOrNull(3_000L) {
            withContext(Dispatchers.IO) { runCatching { probe(url, headers) }.getOrNull() }
        }
        if (kind != null) {
            LogBus.i(TAG, "探测到流类型 $kind：${url.take(100)}")
            putHint(url, kind)
        }
        return kind
    }

    private fun probe(url: String, headers: Map<String, String>): Kind? {
        val client = CmsApi.defaultClient()
        val b = Request.Builder().url(url).get().header("Range", "bytes=0-511")
        PlayHeaders.normalize(headers).forEach { (k, v) -> runCatching { b.header(k, v) } }
        client.newCall(b.build()).execute().use { resp ->
            if (!(resp.isSuccessful || resp.code == 206)) return null
            inferFromContentType(resp.header("Content-Type"))?.let { if (it != Kind.PROGRESSIVE) return it }
            val head = resp.body?.byteStream()?.use { ins ->
                val buf = ByteArray(512)
                var n = 0
                while (n < buf.size) {
                    val r = ins.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                buf.copyOf(n)
            }
            return inferFromBytes(head) ?: inferFromContentType(resp.header("Content-Type"))
        }
    }
}
