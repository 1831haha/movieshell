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
        return resolveUris(M3u8AdFilter.filter(ruled, url), url)
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
            val resolved = base.resolve(uri)?.toString() ?: continue
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
                    "URI=\"$resolved\""
                }
                t.isEmpty() -> line
                else -> base.resolve(t)?.toString() ?: line
            }
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
