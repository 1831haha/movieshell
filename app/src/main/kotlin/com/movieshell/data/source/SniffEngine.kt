package com.movieshell.data.source

import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 嗅探引擎（v0.10）：所有"这个 URL 是不是可播直链"的判定收敛到这里，
 * WebSniffScreen（手动嗅探页）与 ParseApi.sniffType0（type=0 解析线路）共用。
 *
 * 三层判定：
 * 1. 后缀直判（零成本）：.m3u8/.mp4/.mpd/.aac 直接命中；rtmp 标"需外部播放"。
 * 2. 可疑 URL 探测（按需 HEAD）：无后缀但 path/query 像媒体地址的，
 *    发 HEAD（失败回退 Range GET）看 Content-Type 判定。只对可疑 URL 做，
 *    不全量探测，避免把图片/接口请求都打一遍。
 * 3. iframe 剥壳：[extractIframeSrcs] 从页面 HTML 抠 iframe src，逐个走第 2 条探测。
 */
object SniffEngine {

    private const val TAG = "SniffEngine"

    /** 探测超时：单个 URL 的 Content-Type 判定最多等 5 秒 */
    private const val PROBE_TIMEOUT_MS = 5_000L

    /** 嗅探命中种类 */
    enum class SniffKind {
        /** 可直接丢给播放器：m3u8/mp4/mpd/aac */
        DIRECT,
        /** rtmp 流：App 内播不了，标"需外部播放" */
        RTMP,
    }

    /**
     * 直链判定（纯函数，可单元测试）：
     * 只看 URL 的 path 部分（去掉 query/fragment 后）是否以媒体后缀结尾，忽略大小写。
     * 带 query 的（如 xxx.m3u8?token=abc）同样命中。
     * v0.10 在 .m3u8/.mp4 基础上加 .mpd（DASH）/.aac。
     */
    fun isPlayableDirectUrl(url: String): Boolean =
        sniffKind(url) == SniffKind.DIRECT

    /**
     * 后缀判定（纯函数，可单元测试）：
     * - DIRECT：.m3u8/.mp4/.mpd/.aac
     * - RTMP：rtmp:// 开头或 .rtmp 后缀
     * - null：都不是
     */
    fun sniffKind(url: String): SniffKind? {
        val lower = url.lowercase()
        if (lower.startsWith("rtmp://")) return SniffKind.RTMP
        val path = lower.substringBefore('#').substringBefore('?')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".mp4") ||
                path.endsWith(".mpd") || path.endsWith(".aac") -> SniffKind.DIRECT
            path.endsWith(".rtmp") -> SniffKind.RTMP
            else -> null
        }
    }

    /** 明确不是媒体的静态资源后缀：这类 URL 连探测都不做 */
    private val STATIC_EXTS = setOf(
        ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".otf", ".eot", ".json", ".xml", ".txt",
        ".html", ".htm",
    )

    /** path/query 里出现这些词就值得探测一次 Content-Type */
    private val MEDIA_HINTS = setOf(
        "video", "play", "stream", "media", "vod", "live", "m3u8", "mp4",
        "movie", "film", "player", "episode", "hls", "dash", "audio", "mpd",
    )

    /**
     * 可疑 URL 判定（纯函数，可单元测试）：
     * http(s) + 非静态资源 + path/query 含媒体关键词 → 值得发一次 HEAD 探测。
     * 关键词故意保守：宁可漏掉（用户手动点播兜底），不把全站请求都 HEAD 一遍。
     */
    fun isSuspiciousMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        val path = lower.substringBefore('#').substringBefore('?')
        if (STATIC_EXTS.any { path.endsWith(it) }) return false
        val haystack = lower.substringBefore('#')
        return MEDIA_HINTS.any { it in haystack }
    }

    /**
     * Content-Type → 命中种类（纯函数，可单元测试）：
     * 只认播放器能直接播的类型；其余一律 null。
     */
    fun contentTypeKind(contentType: String?): SniffKind? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when (ct) {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/dash+xml",
            "video/mp4", "video/x-m4v",
            "audio/aac", "audio/x-aac", "audio/mp4", "audio/mpeg" -> SniffKind.DIRECT
            else -> null
        }
    }

    /**
     * 无后缀流探测：HEAD 取 Content-Type 判定；HEAD 被拒（405/501）或无
     * Content-Type 时回退 Range GET（只取 0 字节，看响应头）。
     * 单 URL 最多等 [PROBE_TIMEOUT_MS]；任何异常返回 null。
     */
    suspend fun probeContentType(url: String, client: OkHttpClient): SniffKind? =
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                headKind(url, client) ?: rangeGetKind(url, client)
            }
        }

    private fun headKind(url: String, client: OkHttpClient): SniffKind? = runCatching {
        val req = Request.Builder().url(url).head()
            .header("User-Agent", DEFAULT_UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            contentTypeKind(resp.header("Content-Type"))?.also {
                LogBus.d(TAG, "HEAD 命中 $url -> ${resp.header("Content-Type")}")
            }
        }
    }.getOrNull()

    private fun rangeGetKind(url: String, client: OkHttpClient): SniffKind? = runCatching {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", DEFAULT_UA)
            .header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { resp ->
            if (!(resp.isSuccessful || resp.code == 206)) return null
            contentTypeKind(resp.header("Content-Type"))?.also {
                LogBus.d(TAG, "Range 命中 $url -> ${resp.header("Content-Type")}")
            }
        }
    }.getOrNull()

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val IFRAME_SRC =
        "<iframe[^>]*?\\ssrc\\s*=\\s*[\"']([^\"']+)[\"']".toRegex(RegexOption.IGNORE_CASE)

    /**
     * iframe 剥壳（纯函数，可单元测试）：从 HTML 里抠所有 iframe 的 src，
     * 相对地址按 baseUrl 补全；去重保序。
     */
    fun extractIframeSrcs(html: String, baseUrl: String = ""): List<String> {
        val out = LinkedHashSet<String>()
        for (m in IFRAME_SRC.findAll(html)) {
            val src = m.groupValues[1].trim()
            if (src.isBlank() || src.startsWith("javascript:") || src.startsWith("data:")) continue
            out += resolveUrl(baseUrl, src)
        }
        return out.toList()
    }

    /** 相对地址补全（纯函数）：//host、/path、path 三种写法 */
    fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("rtmp://")) return ref
        if (ref.startsWith("//")) {
            val scheme = base.substringBefore("://").takeIf { it != base } ?: "https"
            return "$scheme:$ref"
        }
        if (base.isBlank()) return ref
        return runCatching {
            val b = base.substringBefore('#').substringBefore('?')
            if (ref.startsWith("/")) {
                val origin = b.substringBefore("://") + "://" + b.substringAfter("://").substringBefore("/")
                origin + ref
            } else {
                b.substringBeforeLast("/") + "/" + ref
            }
        }.getOrDefault(ref)
    }
}
