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
     * 阻塞调用（跑在 MpvPlayerManager 的 executor 上）：返回本地过滤后播放列表的
     * 绝对路径；不适用 / 下载失败 / 不是 m3u8 时返回 null。
     */
    fun prepareFilteredPlaylist(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        live: Boolean = false,
    ): String? {
        if (!SniffConfig.adFilterEnabled) return null
        if (live) return null
        if (!AdFilteringDataSource.isPlaylistRequest(url, null)) return null
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        val file = File(dir, sha256(url) + ".m3u8")
        // v1.1.31：同一地址 1 小时内的过滤结果直接复用——点播播放列表是静态的，
        // 换内核重试/同集重播不用每次都重新拉一次
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 3_600_000L) {
            LogBus.i("MpvAdFilter", "去广告：复用 1 小时内缓存 ${file.name}")
            return file.absolutePath
        }
        return runCatching {
            val client = HttpClient.defaultClient().newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
            val text = download(client, url, headers) ?: return null
            val filtered = filterPlaylist(client, url, headers, text) ?: return null
            pruneCache(dir)
            file.writeText(filtered, Charsets.UTF_8)
            LogBus.i("MpvAdFilter", "去广告：${url.take(80)} -> 本地 ${file.name}（${filtered.length} 字符）")
            file.absolutePath
        }.onFailure {
            LogBus.w("MpvAdFilter", "过滤失败，走原地址：${it.message}")
        }.getOrNull()
    }

    /** 缓存淘汰：只留最新的 50 个、7 天内的——同一地址的过滤文件不设上限会一直涨。 */
    private fun pruneCache(dir: File) {
        runCatching {
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            val now = System.currentTimeMillis()
            val sevenDays = 7L * 24 * 60 * 60 * 1000
            files.forEachIndexed { idx, f ->
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
            val text = bytes.toString(Charsets.UTF_8)
            val ct = resp.header("Content-Type").orEmpty()
            if (!text.trimStart().startsWith("#EXTM3U") && !ct.contains("mpegurl", ignoreCase = true)) return null
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
     * v1.3.17：给媒体播放列表补 HLS 标记。CDN 的 splitOut 这类极简 m3u8
     * 只有 #EXTM3U / #EXT-X-VERSION / #EXTINF，没有 TARGETDURATION、
     * MEDIA-SEQUENCE、STREAM-INF；libavformat 的 hls probe 只认后三者，
     * 认不出就拒收（ffprobe 实测：缺两标记报 Invalid data，补上即认出
     * hls），mpv 退回 playlist 解复用器，把每个分片当独立条目逐个播，
     * 每条触发一次 END_FILE、App 跟着逐条判失败。主播放列表（无 EXTINF）
     * 不动；已有标记的不改值。纯函数，可单测。
     */
    internal fun ensureHlsMarkers(content: String): String {
        val lines = content.lines()
        if (lines.none { it.trim().startsWith("#EXTINF") }) return content
        val hasTarget = lines.any { it.trim().startsWith("#EXT-X-TARGETDURATION") }
        val hasSequence = lines.any { it.trim().startsWith("#EXT-X-MEDIA-SEQUENCE") }
        if (hasTarget && hasSequence) return content
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

    /** 相对地址改绝对：分片行与 #EXT-X-KEY / #EXT-X-MAP 的 URI="..." 属性。 */
    internal fun resolveUris(content: String, baseUrl: String): String {
        val base = baseUrl.toHttpUrlOrNull() ?: return content
        return content.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.startsWith("#") -> UriAttr.replace(line) { m ->
                    val resolved = base.resolve(m.groupValues[1])?.toString() ?: m.groupValues[1]
                    "URI=\"" + resolveKeepingQuery(base, resolved) + "\""
                }
                t.isEmpty() -> line
                else -> base.resolve(t)?.toString()?.let { resolveKeepingQuery(base, it) } ?: line
            }
        }
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
