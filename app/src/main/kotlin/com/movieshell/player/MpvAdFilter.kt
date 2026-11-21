package com.movieshell.player

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.core.net.HttpClient
import com.movieshell.data.source.SniffConfig
import com.movieshell.data.source.SniffRules
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * v1.1.31：libmpv 链路的 m3u8 去广告。
 *
 * Media3 侧由 [AdFilteringDataSource] 在 DataSource 层逐个播放列表过滤；libmpv
 * 直接 loadfile 远端地址，拦截不到后续请求——改在起播前把播放列表拉下来，
 * 用同一套 [M3u8AdFilter] 过滤、相对地址改写为绝对地址、写本地缓存文件再播文件。
 *
 * - 不另开本地端口（引擎零端口铁律）；直播不走这里（播放列表持续更新，
 *   静态快照播一段就断）。
 * - 主播放列表（#EXT-X-STREAM-INF）取最高码率分路过滤后直播；分路拉不到
 *   退回过滤后的主列表（相对地址已改绝对），分路再由 mpv 自取（不过滤）。
 * - 任何一步失败返回 null，调用方走原地址起播，不影响播放。
 */
object MpvAdFilter {

    private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    private const val CACHE_DIR = "ad_filtered"
    private val UriAttr = Regex("URI=\"([^\"]+)\"")

    /**
     * v1.4.6 A1.1：上次过滤结果的种类（供首屏诊断记过滤下载备注段；NONE=未尝试，
     * 如开关关/直播/非播放列表；FAILED=下载或过滤失败后走原地址）。
     */
    enum class PrepareKind { NONE, CACHE, DOWNLOADED, FAILED }

    @Volatile
    var lastPrepareKind: PrepareKind = PrepareKind.NONE
        private set

    /**
     * 阻塞调用（跑在 MpvPlayerManager 的 executor 上）：返回本地过滤后播放列表的
     * 绝对路径；不适用 / 下载失败 / 不是 m3u8 时返回 null。
     */
    fun prepareFilteredPlaylist(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        live: Boolean = false,
    ): String? {
        if (!SniffConfig.adFilterEnabled) {
            lastPrepareKind = PrepareKind.NONE
            return null
        }
        if (live) {
            lastPrepareKind = PrepareKind.NONE
            return null
        }
        // M28：后缀不明时不再直接放行失败——无后缀 HLS（/play.php?id= 等）
        // 在 isPlaylistRequest(url, null) 里拿不到头、只剩后缀判断，直接
        // 返回 null 就永远走不到过滤。只有明确不是播放列表的地址才提前
        // 返回；其余的先下载，download() 再按 Content-Type 与 #EXTM3U
        // 内容判定，不是 m3u8 就返回 null。
        if (isDefinitelyNotPlaylist(url)) {
            lastPrepareKind = PrepareKind.NONE
            return null
        }
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        // v1.3.21 H13：缓存键 v3，混入请求头摘要——同一地址换 Cookie/
        // Referer（如夸克 __pugs 续期、清 Referer 重试）是不同的播放列表，
        // 旧键只认 URL 会把旧文件当新鲜用。v2 盐文件自然失效（新键名不同），
        // 由 pruneCache 慢慢清掉。
        val file = File(dir, cacheKey(url, headers) + ".m3u8")
        // v1.1.31：同一地址 1 小时内的过滤结果直接复用——点播播放列表是静态的，
        // 换内核重试/同集重播不用每次都重新拉一次
        // v1.3.21 H14：命中也要校验（存在、1 小时内、非空、首行是 #EXTM3U），
        // 否则重下——半截写盘的坏文件不再被当成好缓存复用。
        if (isCacheValid(file)) {
            LogBus.i("MpvAdFilter", "去广告：复用 1 小时内缓存 ${file.name}")
            lastPrepareKind = PrepareKind.CACHE
            return file.absolutePath
        }
        val result = runCatching {
            val client = HttpClient.defaultClient().newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
            val text = download(client, url, headers)
            if (text == null) {
                lastPrepareKind = PrepareKind.FAILED
                return null
            }
            val filtered = filterPlaylist(client, url, headers, text)
            if (filtered == null) {
                lastPrepareKind = PrepareKind.FAILED
                return null
            }
            pruneCache(dir)
            // v1.3.19：写盘前再去一次行首杂质（链路里任何一步都可能留出空行），
            // 并把首行打进日志——下次再逐条播，先看首行是不是 #EXTM3U。
            val cleaned = stripLeadingJunk(filtered)
            // v1.3.21 H14：先写 tmp 再原子 rename——写一半崩掉不再留
            // 半截文件冒充缓存；rename 失败才直接写。
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(cleaned, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(cleaned, Charsets.UTF_8)
                tmp.delete()
            }
            val firstLine = cleaned.substringBefore('\n').take(20)
            LogBus.i("MpvAdFilter", "去广告：${url.take(80)} -> 本地 ${file.name}（${cleaned.length} 字符，首行=${firstLine}）")
            file.absolutePath
        }.onFailure {
            LogBus.w("MpvAdFilter", "过滤失败，走原地址：${it.message}")
        }.getOrNull()
        lastPrepareKind = if (result != null) PrepareKind.DOWNLOADED else PrepareKind.FAILED
        return result
    }

    /**
     * v1.3.21 H13：缓存键——URL + 影响播放的三个请求头的摘要。
     * 只取 Cookie/Referer/Authorization，键名小写排序、值拼串后 sha256，
     * 头的顺序不影响键。纯函数，可单测。
     */
    internal fun cacheKey(url: String, headers: Map<String, String>): String {
        val digest = headers.entries
            .filter { (k, v) -> k.lowercase() in setOf("cookie", "referer", "authorization") && v.isNotBlank() }
            .sortedBy { it.key.lowercase() }
            .joinToString("&") { (k, v) -> "${k.lowercase()}=$v" }
            .let { sha256(it) }
        return sha256("v3:$url|$digest")
    }

    /**
     * M28：明确不是播放列表才返回 true——非 http(s) 地址、已登记或按
     * 后缀能认出的点播文件（.mp4/.ts/.m4s/.key 等）、DASH。无后缀 HLS
     * 与未知地址返回 false，由 download() 按内容再判。纯函数，可单测。
     */
    internal fun isDefinitelyNotPlaylist(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return true
        when (MediaTypeSniffer.hint(url)) {
            MediaTypeSniffer.Kind.PROGRESSIVE, MediaTypeSniffer.Kind.DASH -> return true
            else -> {}
        }
        val path = lower.substringBefore("?").substringBefore("#")
        return path.endsWith(".ts") || path.endsWith(".mp4") ||
            path.endsWith(".m4s") || path.endsWith(".key")
    }

    /** v1.3.21 H14：缓存命中校验——存在、1 小时内、非空、首行是 #EXTM3U。 */
    private fun isCacheValid(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        if (System.currentTimeMillis() - file.lastModified() >= 3_600_000L) return false
        return runCatching {
            file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLine()?.trimStart()?.startsWith("#EXTM3U") == true
            }
        }.getOrDefault(false)
    }

    /** 缓存淘汰：只留最新的 50 个、7 天内的——同一地址的过滤文件不设上限会一直涨。 */
    private fun pruneCache(dir: File) {
        runCatching {
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            val now = System.currentTimeMillis()
            val sevenDays = 7L * 24 * 60 * 60 * 1000
            files.forEachIndexed { idx, f ->
                // L22：1 小时内改过的文件可能正在播（mpv 已打开），跳过不删；
                // 1 小时也是缓存复用期，删了它下次还得重下。
                if (now - f.lastModified() < 3_600_000L) return@forEachIndexed
                if (idx >= 50 || now - f.lastModified() > sevenDays) f.delete()
            }
        }
    }

    private fun download(client: OkHttpClient, url: String, headers: Map<String, String>): String? {
        val builder = Request.Builder().url(url)
        for ((k, v) in headers) if (v.isNotBlank()) builder.header(k, v)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val bytes = body.bytes()
            if (bytes.size > MAX_PLAYLIST_BYTES) return null
            // v1.3.19：先去行首杂质再判断——源站行首若有空行/空格/BOM，
            // trimStart 检查能过、写盘却原样保留，hls probe 认不出 #EXTM3U。
            val text = stripLeadingJunk(bytes.toString(Charsets.UTF_8))
            val ct = resp.header("Content-Type").orEmpty()
            if (!text.startsWith("#EXTM3U") && !ct.contains("mpegurl", ignoreCase = true)) return null
            return text
        }
    }

    private fun filterPlaylist(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        text: String,
    ): String? {
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        // 主播放列表：取最高码率分路，过滤分路；分路拉不到退回过滤后的主列表
        if (text.contains("#EXT-X-STREAM-INF")) {
            pickBestVariant(text, url)?.let { variantUrl ->
                download(client, variantUrl, headers)?.let { variantText ->
                    return filterMediaPlaylist(variantText, variantUrl)
                }
            }
        }
        return filterMediaPlaylist(text, url)
    }

    private fun filterMediaPlaylist(text: String, url: String): String {
        // 与 AdFilteringDataSource 同序：订阅 rules 正文正则先跑，再做统计判定
        val ruled = runCatching {
            M3u8AdFilter.applyRuleRegex(text, SniffRules.m3u8BodyRegex(url))
        }.getOrDefault(text)
        // v1.3.17：写盘前补 HLS 标记（见 ensureHlsMarkers），否则 lavf
        // 认不出极简播放列表，mpv 会把分片当独立条目逐个播。
        return ensureHlsMarkers(resolveUris(M3u8AdFilter.filter(ruled, url), url))
    }

    /**
     * v1.3.19：去行首杂质（空行/空格/BOM）。源站播放列表行首若有这些，
     * hls probe 的 strncmp 认不出 #EXTM3U（回 0 分），mpv 退回 playlist
     * 解复用器逐条播分片；前面的 trimStart 检查只管"认不认"，写盘必须真去掉。
     * 1.3.18 的 probescore=25 对 0 分无效，白改了（ffprobe 实测：行首多个
     * 空行/空格/BOM 都报 Invalid data，25 分的前提是文件本身能探出分）。
     * 纯函数，可单测。
     */
    internal fun stripLeadingJunk(content: String): String {
        var out = content.trimStart()
        while (out.startsWith("\uFEFF")) {
            out = out.substring(1).trimStart()
        }
        return out
    }

    /**
     * v1.3.17：给媒体播放列表补 HLS 标记。CDN 的 splitOut 这类极简 m3u8
     * 只有 #EXTM3U / #EXT-X-VERSION / #EXTINF，没有 TARGETDURATION、
     * MEDIA-SEQUENCE、STREAM-INF；libavformat 的 hls probe 只认后三者，
     * 认不出就拒收（ffprobe 实测：缺两标记报 Invalid data，补上即认出
     * hls），mpv 退回 playlist 解复用器，把每个分片当独立条目逐个播，
     * 每条触发一次 END_FILE、App 跟着逐条判失败。主播放列表（无 EXTINF）
     * 不动；已有标记的不改值。M30：媒体列表再补 #EXT-X-ENDLIST，缺它
     * 会被当直播处理（时长未知、播完不发 EOF）。
     * 纯函数，可单测。
     */
    internal fun ensureHlsMarkers(content: String): String {
        val lines = content.lines()
        if (lines.none { it.trim().startsWith("#EXTINF") }) return content
        val hasTarget = lines.any { it.trim().startsWith("#EXT-X-TARGETDURATION") }
        val hasSequence = lines.any { it.trim().startsWith("#EXT-X-MEDIA-SEQUENCE") }
        val hasEndList = lines.any { it.trim() == "#EXT-X-ENDLIST" }
        if (hasTarget && hasSequence && hasEndList) return content
        var maxDur = 0.0
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXTINF:")) {
                t.substringAfter(":").substringBefore(",").trim().toDoubleOrNull()?.let {
                    if (it > maxDur) maxDur = it
                }
            }
        }
        val target = kotlin.math.ceil(maxDur).toInt().coerceAtLeast(1)
        val insert = buildList {
            if (!hasTarget) add("#EXT-X-TARGETDURATION:" + target)
            if (!hasSequence) add("#EXT-X-MEDIA-SEQUENCE:0")
        }
        val anchor = lines.indexOfFirst { it.trim().startsWith("#EXT-X-VERSION") }
            .takeIf { it >= 0 }
            ?: lines.indexOfFirst { it.trim() == "#EXTM3U" }
        val out = lines.toMutableList()
        if (anchor >= 0) out.addAll(anchor + 1, insert) else out.addAll(0, insert)
        // M30：媒体列表补 #EXT-X-ENDLIST——缺它会被当直播处理（时长未知、
        // 播完不发 EOF）；主列表无 EXTINF，早在上面原样返回了。
        if (!hasEndList) {
            var at = out.size
            while (at > 0 && out[at - 1].isBlank()) at--
            out.add(at, "#EXT-X-ENDLIST")
        }
        return out.joinToString("\n")
    }

    /** 主播放列表里取最高码率分路的绝对地址；不是主列表返回 null。 */
    internal fun pickBestVariant(content: String, baseUrl: String): String? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val lines = content.lines()
        var best: Pair<Long, String>? = null
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(line)
                ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1)
                .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
                ?.trim() ?: continue
            val resolved = base.resolve(uri)?.toString()?.let { resolveKeepingQuery(base, it) } ?: continue
            if (best == null || bw > best.first) best = bw to resolved
        }
        return best?.second
    }

    /**
     * 相对地址改绝对：分片行与 #EXT-X-KEY / #EXT-X-MAP 的 URI="..." 属性。
     * L24：只接受 http/https；其余 scheme 的行整段丢弃，不让 mpv 打开本地文件。
     */
    internal fun resolveUris(content: String, baseUrl: String): String {
        val base = baseUrl.toHttpUrlOrNull() ?: return content
        val out = ArrayList<String>()
        // 最近一个还没等到 URI 的 EXTINF / STREAM-INF 行在 out 里的下标；
        // 它所属的段被丢弃时连同中间标签行一起删，不留裸 EXTINF。
        var pendingTagIdx: Int? = null
        for (line in content.lines()) {
            val t = line.trim()
            when {
                t.startsWith("#") -> {
                    var badScheme = false
                    val replaced = UriAttr.replace(line) { m ->
                        val raw = m.groupValues[1]
                        val resolved = base.resolve(raw)?.toString() ?: raw
                        if (!isHttpUrl(resolved)) badScheme = true
                        "URI=\"" + resolveKeepingQuery(base, resolved) + "\""
                    }
                    // L24：URI 属性只接受 http/https；file 等 scheme 整行丢弃。
                    if (badScheme) continue
                    if (t.startsWith("#EXTINF:") || t.startsWith("#EXT-X-STREAM-INF")) {
                        pendingTagIdx?.let { idx ->
                            while (out.size > idx) out.removeAt(out.size - 1)
                        }
                        pendingTagIdx = out.size
                    }
                    out.add(replaced)
                }
                t.isEmpty() -> out.add(line)
                else -> {
                    val resolved = base.resolve(t)?.toString()
                        ?.let { resolveKeepingQuery(base, it) }
                    if (resolved != null && isHttpUrl(resolved)) {
                        out.add(resolved)
                        pendingTagIdx = null
                    } else {
                        // L24：解析不出 http(s) 地址（file 等异种 scheme、
                        // 畸形地址）整段丢弃；相对地址解析失败同样丢弃——
                        // 写盘后是本地文件，相对地址会被 mpv 按本地路径打开。
                        pendingTagIdx?.let { idx ->
                            while (out.size > idx) out.removeAt(out.size - 1)
                        }
                        pendingTagIdx = null
                    }
                }
            }
        }
        return out.joinToString("\n")
    }

    /** 纯函数：只认 http/https。 */
    internal fun isHttpUrl(url: String): Boolean {
        val scheme = url.toHttpUrlOrNull()?.scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /**
     * v1.3.15：相对地址解析后丢了原 query（auth_key 这类 token）——分片/分路请求没
     * token 可能被拒。只在同 host 且结果本身没 query 时把原 query 补回去；
     * 跨 host 或已有 query 的不动。纯函数，可单测。
     */
    internal fun resolveKeepingQuery(base: okhttp3.HttpUrl, resolved: String): String {
        val query = base.query
        if (query.isNullOrBlank()) return resolved
        val target = resolved.toHttpUrlOrNull() ?: return resolved
        if (target.host != base.host) return resolved
        if (!target.query.isNullOrBlank()) return resolved
        return target.newBuilder().query(query).build().toString()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
