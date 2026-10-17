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
 * - 封面：GET {BASE}?types=pic&source=netease&id={pic_id}（直接返回图片）
 */
object MusicApi {
    private const val TAG = "MusicApi"
    private const val BASE = "https://music-api.gdstudio.xyz/api.php"
    private const val SOURCE = "netease"

    data class Track(
        val id: String,
        val name: String,
        val artist: String,
        val album: String,
        val picId: String,
        val urlId: String,
    )

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

    /** 取可播放地址（url_id → 直链） */
    suspend fun playUrl(urlId: String): String {
        val body = get("$BASE?types=url&source=$SOURCE&id=$urlId")
        val url = runCatching { JSONObject(body).optString("url") }.getOrDefault("")
        if (url.isBlank()) throw IllegalStateException("未取到播放地址")
        return url
    }

    /** 封面图地址 */
    fun picUrl(picId: String): String =
        if (picId.isBlank()) "" else "$BASE?types=pic&source=$SOURCE&id=$picId"
}
