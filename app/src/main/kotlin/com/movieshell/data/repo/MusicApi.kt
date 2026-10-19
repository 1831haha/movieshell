package com.movieshell.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v0.79.0：在线音乐 API（GD Studio 聚合接口，2026-10-17 实测可用）。
 *
 * - 搜索：GET {BASE}?types=search&source=netease&name={q}&count={n}&pages={p}
 * - 播放地址：GET {BASE}?types=url&source=netease&id={url_id} → {"url": "..."}
 * - 封面：GET {BASE}?types=pic&source=netease&id={pic_id} → {"url": "真实图片地址"}
 *   （v0.89.0 实测：并非直接返回图片，而是包一层 {"url":...}，须经 picImageUrl 解析一次）
 */
object MusicApi {
    private const val TAG = "MusicApi"
    private const val BASE = "https://music-api.gdstudio.xyz/api.php"
    private const val SOURCE = "netease"

    // v0.81.0：@Serializable 以便本地音乐/收藏持久化；localUri 存本地音频的 content URI
    @kotlinx.serialization.Serializable
    data class Track(
        val id: String,
        val name: String,
        val artist: String,
        val album: String,
        val picId: String,
        val urlId: String,
        val localUri: String = "",
    ) {
        val isLocal: Boolean get() = localUri.isNotBlank()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("音乐接口请求失败：${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    /** 搜索歌曲 */
    suspend fun search(query: String, page: Int = 1, count: Int = 20): List<Track> {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = get("$BASE?types=search&source=$SOURCE&name=$q&count=$count&pages=$page")
        val tracks = mutableListOf<Track>()
        runCatching {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val artists = o.optJSONArray("artist")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
                }.orEmpty()
                tracks += Track(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    artist = artists.joinToString("、").ifBlank { "未知歌手" },
                    album = o.optString("album"),
                    picId = o.optString("pic_id"),
                    urlId = o.optString("url_id"),
                )
            }
        }.onFailure {
            LogBus.w(TAG, "音乐搜索解析失败：${it.message}")
        }
        LogBus.i(TAG, "音乐搜索「$query」：${tracks.size} 条")
        return tracks
    }

    /** v0.82.0：歌单导入——网易云歌单 ID/链接 → 歌单名 + 曲目列表。 */
    suspend fun playlist(idOrUrl: String): Pair<String, List<Track>> {
        // v0.107.0 P0-8：优先取 id= 参数——直接取首个数字串会把 music.163.com 里的 "163" 当成歌单 ID
        val id = Regex("[?&]id=(\\d+)").find(idOrUrl)?.groupValues?.get(1)
            ?: Regex("[0-9]+").find(idOrUrl)?.value
            ?: throw IllegalStateException("没识别出歌单 ID")
        val body = get("$BASE?types=playlist&source=$SOURCE&id=$id")
        val tracks = mutableListOf<Track>()
        val name = runCatching {
            val root = JSONObject(body)
            val pl = root.getJSONObject("playlist")
            val arr = pl.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sid = o.optLong("id", 0L).toString()
                if (sid == "0") continue
                val ar = o.optJSONArray("ar")
                val artists = (0 until (ar?.length() ?: 0))
                    .mapNotNull { ar!!.optJSONObject(it)?.optString("name")?.ifBlank { null } }
                val al = o.optJSONObject("al")
                tracks += Track(
                    id = sid,
                    name = o.optString("name").ifBlank { "未知曲目" },
                    artist = artists.joinToString("、").ifBlank { "未知歌手" },
                    album = al?.optString("name").orEmpty(),
                    picId = al?.optString("pic_str").orEmpty(),
                    urlId = sid,
                )
            }
            pl.optString("name").ifBlank { "歌单 $id" }
        }.getOrElse { throw IllegalStateException("歌单解析失败") }
        if (tracks.isEmpty()) throw IllegalStateException("歌单是空的或已下架")
        LogBus.i(TAG, "歌单导入成功")
        return name to tracks
    }

    /** v0.83.0：单行歌词 */
    data class LyricLine(val timeMs: Long, val text: String)

    /** v0.83.0：拉歌词（LRC）并解析；无歌词返回空列表。 */
    suspend fun lyric(track: Track): List<LyricLine> {
        val lid = track.urlId.ifBlank { return emptyList() }
        val body = runCatching {
            get("$BASE?types=lyric&source=$SOURCE&id=$lid")
        }.getOrNull() ?: return emptyList()
        val raw = runCatching { JSONObject(body).optString("lyric") }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        val pat = Regex("""\[(\d+):(\d+)(?:\.(\d+))?\]""")
        val lines = mutableListOf<LyricLine>()
        for (ln in raw.lineSequence()) {
            // v0.99.0 P1-10：一行多个时间戳（如 [00:12.00][00:25.00]歌词）都要保留
            val matches = pat.findAll(ln).toList()
            if (matches.isEmpty()) continue
            val text = ln.substring(matches.last().range.last + 1).trim()
            if (text.isBlank()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val cs = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                lines += LyricLine(min * 60000 + sec * 1000 + cs, text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** 取可播放地址（url_id → 直链） */
    suspend fun playUrl(urlId: String): String {
        val body = get("$BASE?types=url&source=$SOURCE&id=$urlId")
        val url = runCatching { JSONObject(body).optString("url") }.getOrDefault("")
        if (url.isBlank()) throw IllegalStateException("未取到播放地址")
        return url
    }

    /**
     * v0.89.0：封面真实图片地址。
     *
     * types=pic 实际返回 {"url": "https://p2.music.126.net/...jpg"}（包一层，不是直出图片），
     * 这里解析一次；结果进内存缓存，列表滚动不反复请求；失败返回 ""。
     */
    private val picCache = mutableMapOf<String, String>()

    suspend fun picImageUrl(picId: String): String {
        if (picId.isBlank()) return ""
        synchronized(picCache) { picCache[picId]?.let { return it } }
        val url = runCatching {
            JSONObject(get("$BASE?types=pic&source=$SOURCE&id=$picId")).optString("url")
        }.getOrDefault("")
        if (url.isNotBlank()) synchronized(picCache) { picCache[picId] = url }
        return url
    }
}
