package com.movieshell.data.source

import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 嗅探引擎（v0.92）：所有"这个 URL 是不是可播直链"的判定收敛到这里，
 * WebSniffScreen（手动嗅探页）与 ParseApi.sniffType0（type=0 解析线路）共用。
 *
 * 判定：
 * 1. 后缀直判（零成本）：.m3u8/.mp4/.mkv/.flv/.avi/.webm/.mp3/.m4a/.aac/.mov/.wmv/.mpd
 *    直接命中；另认 `/m3u8` 路径形式（DsPlayer 口径）；rtmp 标"需外部播放"。
 *    例外：.ts 是 m3u8 分片，不直接命中——只在深度嗅探/HTML 深挖里当线索
 *    （同目录拼 playlist 候选再验证）。
 * 2. 可疑 URL 探测（按需 HEAD）：无后缀但 path/query 像媒体地址的，
 *    发 HEAD（失败回退 Range GET）看 Content-Type 判定。只对可疑 URL 做，
 *    不全量探测，避免把图片/接口请求都打一遍。
 * 3. iframe 剥壳：[extractIframeSrcs] 从页面 HTML 抠 iframe src，逐个走第 2 条探测。
 *
 * v0.149（DS 材料包 P0-2 落地，行为描述见 DS 0.9.8 changelog）：
 * - 案例 1 词边界：/m3u8 路径规则加两道防线——静态资源不命中、主 frame 导航不命中
 *   （见 [sniffKind]）；blob 深挖正则要求后缀后有词边界，杜绝 player.m3u8.js
 *   被截断成 player.m3u8 的伪造候选。
 * - 案例 3 结算验证：点选播放前带播放请求头做一次 Content-Type 验证
 *   （见 [settlementDenied]），只拦网页/脚本类硬拒绝，含糊情况放行。
 * - 案例 5 排序：[rankIndex] 给候选面板排序与超时最优候选定级（m3u8 最优）。
 * - CF 真窗口检测：[isChallengePage] 只检测不绕过——本屏本身就是真 WebView，
 *   用户手动过验证后 Cookie 自动续上。
 */
object SniffEngine {

    private const val TAG = "SniffEngine"

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
     * v0.92 按 DsPlayer 口径扩到 12 种后缀 + `/m3u8` 路径形式；.ts 除外（分片不直接播）。
     */
    fun isPlayableDirectUrl(url: String): Boolean =
        sniffKind(url) == SniffKind.DIRECT

    /** 可直接播的媒体后缀（v0.92：DsPlayer 口径；.ts 除外——分片单独拿出来播不了） */
    private val DIRECT_EXTS = setOf(
        ".m3u8", ".mp4", ".mkv", ".flv", ".avi", ".webm",
        ".mp3", ".m4a", ".aac", ".mov", ".wmv", ".mpd",
    )

    /**
     * 后缀判定（纯函数，可单元测试）：
     * - DIRECT：DIRECT_EXTS 后缀，或 `/m3u8` 路径形式（如 https://host/m3u8/abc123）
     * - RTMP：rtmp:// 开头或 .rtmp 后缀
     * - null：都不是（.ts 分片走 [isTsSegment]，不直接命中）
     *
     * v0.16.1：先查用户自定义正则（SniffPatternStore），命中即 DIRECT。
     * v0.149（DS 案例 1/6 的结算防线）：/m3u8 路径形式是启发式规则，只对资源请求生效——
     * ①静态资源（.js/.css/图片等）即使路径含 m3u8 段也不命中（U 酷案：m3u8 词出现在
     * .js 资源里被误判）；②主 frame 导航 URL 不命中（/m3u8/ 路径转换器壳页本身不是流，
     * 播它等于播一页 HTML，让位于剥壳流程：壳页加载后由 iframe/JS 链路抓里面的真流）。
     * 真后缀直链（.m3u8/.mp4 等）不受这两条影响。
     */
    fun sniffKind(url: String, isMainFrame: Boolean = false): SniffKind? {
        // 自定义正则优先
        if (matchesCustomPattern(url)) return SniffKind.DIRECT
        val lower = url.lowercase()
        if (lower.startsWith("rtmp://")) return SniffKind.RTMP
        val path = lower.substringBefore('#').substringBefore('?')
        if (DIRECT_EXTS.any { path.endsWith(it) }) return SniffKind.DIRECT
        // DsPlayer 式：/m3u8 路径形式；排除静态资源与主 frame 导航误伤
        if (!isMainFrame && STATIC_EXTS.none { path.endsWith(it) } &&
            path.split('/').any { it == "m3u8" }
        ) {
            return SniffKind.DIRECT
        }
        if (path.endsWith(".rtmp")) return SniffKind.RTMP
        return null
    }

    /**
     * ts 分片判定（v0.92，纯函数）：m3u8 的分片，不是可播直链。
     * 只在深度嗅探/HTML 深挖里当线索（[tsCluePlaylists]），各拦截链路不直接 onHit。
     */
    fun isTsSegment(url: String): Boolean {
        val path = url.lowercase().substringBefore('#').substringBefore('?')
        return path.endsWith(".ts")
    }

    /**
     * ts 分片线索（v0.92，纯函数）：按分片同目录的常见 playlist 名拼候选。
     * 调用方必须再经 [probeContentType] 验证，禁止直接 onHit。
     */
    fun tsCluePlaylists(tsUrl: String): List<String> {
        if (!isTsSegment(tsUrl)) return emptyList()
        val clean = tsUrl.substringBefore('#').substringBefore('?')
        val dir = clean.substringBeforeLast('/') + "/"
        if (!(dir.startsWith("http://") || dir.startsWith("https://"))) return emptyList()
        return listOf("index.m3u8", "playlist.m3u8").map { dir + it }.distinct()
    }

    /**
     * 自定义正则匹配（v0.16.1）：
     * 用户在实验室页添加的正则，任一匹配即视为可播直链。
     */
    fun matchesCustomPattern(url: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            com.movieshell.data.repo.SniffPatternStore.compiled().any { it.containsMatchIn(url) }
        }.getOrDefault(false)
    }

    /** 明确不是媒体的静态资源后缀：这类 URL 连探测都不做 */
    private val STATIC_EXTS = setOf(
        ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".otf", ".eot", ".json", ".xml", ".txt",
        ".html", ".htm",
    )

    /**
     * v0.94：广告地址特征（drpy 截图里 sniffReg 加强版的负向前瞻思路）。
     * 命中则不进候选列表。大小写不敏感；关键词故意保守，宁可漏放不误杀。
     */
    private val AD_URL_KEYWORDS = listOf(
        "advert", "doubleclick", "googlesyndication", "googleadservices",
        "adsrv", "popad", "adserver", "/ads/", ".ads.", "/ad/",
        "preview",
    )

    /** 广告地址判定（v0.94，纯函数）：URL 含广告特征 → 不进候选列表 */
    fun isAdUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return AD_URL_KEYWORDS.any { it in lower }
    }

    /**
     * v0.94：广告域名黑名单（drpy 截图里 hosts 拦截思路）。
     * shouldInterceptRequest 里 host 命中 → 直接返回空响应拦截请求。
     */
    private val AD_HOST_KEYWORDS = listOf(
        "doubleclick", "googlesyndication", "googleadservices",
        "adsrv", "popad", "adservice", ".ads.", "advert",
        "adnxs", "criteo",
    )

    /** 广告域名判定（v0.94，纯函数）：host 含广告特征 → 拦截 */
    fun isAdHost(host: String): Boolean {
        if (host.isBlank()) return false
        val lower = host.lowercase()
        return AD_HOST_KEYWORDS.any { it in lower }
    }

    /**
     * 静态资源判定（v0.92 公开：深度嗅探过滤候选 URL 用）。
     * 注意 .ts 不在其中——分片由 [isTsSegment] 另行处理。
     */
    fun isStaticResource(url: String): Boolean {
        val path = url.lowercase().substringBefore('#').substringBefore('?')
        return STATIC_EXTS.any { path.endsWith(it) }
    }

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
     * v0.93：按海阔视界口径补齐 mpegurl 变体。
     */
    fun contentTypeKind(contentType: String?): SniffKind? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when (ct) {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/mpegurl",
            "audio/mpegurl", "audio/x-mpegurl",
            "video/vnd.mpegurl", "video/x-mpegurl",
            "application/dash+xml",
            "video/mp4", "video/x-m4v",
            "audio/aac", "audio/x-aac", "audio/mp4", "audio/mpeg" -> SniffKind.DIRECT
            else -> null
        }
    }

    /**
     * 无后缀流探测：HEAD 取 Content-Type 判定；HEAD 被拒（405/501）或无
     * Content-Type 时回退 Range GET（只取 0 字节，看响应头）。
     * 单 URL 最多等 [SniffConfig.probeTimeoutMs]；失败按 [SniffConfig.probeRetry]
     * 重试；任何异常返回 null。
     */
    suspend fun probeContentType(url: String, client: OkHttpClient): SniffKind? {
        val timeoutMs = SniffConfig.probeTimeoutMs
        val retries = SniffConfig.probeRetry.coerceIn(0, 5)
        var attempt = 0
        while (true) {
            val kind = withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    headKind(url, client) ?: rangeGetKind(url, client)
                }
            }
            if (kind != null) return kind
            if (attempt >= retries) return null
            attempt++
            delay(600)
        }
    }

    private fun headKind(url: String, client: OkHttpClient): SniffKind? = runCatching {
        val req = Request.Builder().url(url).head()
            .header("User-Agent", DEFAULT_UA_CHROME120).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            contentTypeKind(resp.header("Content-Type"))?.also {
                LogBus.d(TAG, "HEAD 命中 $url -> ${resp.header("Content-Type")}")
            }
        }
    }.getOrNull()

    private fun rangeGetKind(url: String, client: OkHttpClient): SniffKind? = runCatching {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", DEFAULT_UA_CHROME120)
            .header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { resp ->
            if (!(resp.isSuccessful || resp.code == 206)) return null
            contentTypeKind(resp.header("Content-Type"))?.also {
                LogBus.d(TAG, "Range 命中 $url -> ${resp.header("Content-Type")}")
            }
        }
    }.getOrNull()

    /**
     * v0.149 案例 3 结算硬拒绝的 Content-Type：网页/脚本/样式/图片/JSON——
     * 这类响应绝不可能是视频流（aa1.cn 案：文本截断假地址）。
     * text/plain、octet-stream、无 CT 等含糊情况一律放行（fail-open：真 m3u8
     * 常以 text/plain 下发，播不动播放器会自己报错），避免误杀。
     */
    private val SETTLEMENT_DENY_CT = setOf(
        "text/html", "application/xhtml+xml",
        "application/javascript", "text/javascript",
        "application/json", "text/css",
    )

    /** 结算 Content-Type 判定（纯函数）：硬拒绝类型或图片 → true，其余（含糊/缺失）→ false */
    fun settlementDenied(contentType: String?): Boolean {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return ct in SETTLEMENT_DENY_CT || ct.startsWith("image/")
    }

    /**
     * v0.149 案例 3 结算验证：点选播放前，用播放时将带的请求头（Referer/UA/Cookie）
     * 做一次 HEAD（失败回退 Range GET）取 Content-Type；只有硬拒绝类型才拦下。
     * 单次探测、无重试（点选要跟手）；探测失败/无 CT 一律放行。
     */
    suspend fun settlementDenied(
        url: String,
        client: OkHttpClient,
        headers: Map<String, String>,
    ): Boolean {
        val ct = withTimeoutOrNull(SniffConfig.probeTimeoutMs) {
            withContext(Dispatchers.IO) { fetchContentType(url, client, headers) }
        } ?: return false
        return settlementDenied(ct)
    }

    private fun fetchContentType(
        url: String,
        client: OkHttpClient,
        headers: Map<String, String>,
    ): String? = runCatching {
        val builder = Request.Builder().url(url)
            .header("User-Agent", headers["User-Agent"] ?: DEFAULT_UA_CHROME120)
        headers["Referer"]?.let { builder.header("Referer", it) }
        headers["Cookie"]?.let { builder.header("Cookie", it) }
        client.newCall(builder.head().build()).execute().use { resp ->
            if (resp.isSuccessful) resp.header("Content-Type")?.let { return it }
        }
        client.newCall(builder.get().header("Range", "bytes=0-0").build())
            .execute().use { resp ->
                if (resp.isSuccessful || resp.code == 206) resp.header("Content-Type") else null
            }
    }.getOrNull()

    /**
     * v0.149 案例 5 目标探测排序：候选面板展示顺序与超时最优候选的定级——
     * m3u8 最优，mp4/mpd 次之，其余最后；同级按发现顺序。
     */
    fun rankIndex(url: String): Int {
        val path = url.lowercase().substringBefore('#').substringBefore('?')
        return when {
            path.endsWith(".m3u8") -> 0
            path.endsWith(".mp4") || path.endsWith(".mpd") -> 1
            else -> 2
        }
    }

    /**
     * v0.149 CF 真窗口检测（只检测、不绕过）：URL 或标题含验证平台特征即判定为验证页。
     * 本屏本身就是真 WebView，用户手动过验证后 Cookie 自动续上、嗅探继续；
     * 全自动绕过不做（DS 0.9.8 已知限制原文同款红线）。
     */
    fun isChallengePage(url: String, title: String): Boolean {
        val u = url.lowercase()
        if ("challenge-platform" in u || "cdn-cgi/challenge" in u || "__cf_chl" in u) return true
        return title.contains("Attention Required") || title.contains("人机验证")
    }


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
