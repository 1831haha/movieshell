package com.movieshell.player.danmaku

import android.content.Context
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v0.54：弹幕数据源（弹弹play 公开 API，无需登录）。
 * 按标题搜索番剧 -> 取弹幕库 ID -> 拉取弹幕列表。
 * 失败静默返回空（无弹幕不影响播放）。
 */
object DanmakuSource {
    private const val TAG = "Danmaku"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class SearchResp(val animes: List<Anime> = emptyList())
    @Serializable
    private data class Anime(val animeId: Long = 0, val animeTitle: String = "")
    @Serializable
    private data class CommentResp(val comments: List<Comment> = emptyList())
    @Serializable
    private data class Comment(
        val cid: Long = 0,
        val p: String = "", // "时间,模式,颜色,用户"
        val m: String = "",
    )

    /**
     * 按标题搜弹幕。返回按 timeMs 排序的列表；搜不到/失败返回空。
     * episode 用于多集时尽量匹配对应集（弹弹play 按番剧+集数取）。
     */
    suspend fun fetch(title: String, episode: Int = 1): List<Danmaku> = withContext(Dispatchers.IO) {
        try {
            val animeId = searchAnime(title) ?: return@withContext emptyList()
            val comments = fetchComments(animeId, episode)
            comments.mapNotNull { c ->
                val parts = c.p.split(",")
                if (parts.size < 3 || c.m.isBlank()) return@mapNotNull null
                val timeSec = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val mode = parts[1].toIntOrNull() ?: 1
                val colorInt = parts[2].toIntOrNull() ?: 16777215
                // 弹弹play mode: 1=滚动 4=底部 5=顶部；转成我们的 type
                val type = when (mode) {
                    4 -> Danmaku.TYPE_BOTTOM
                    5 -> Danmaku.TYPE_TOP
                    else -> Danmaku.TYPE_SCROLL
                }
                // 颜色是十进制 RGB，转 ARGB（不透明）
                val argb = 0xFF000000.toInt() or (colorInt and 0xFFFFFF)
                Danmaku(
                    text = c.m.take(60),
                    timeMs = (timeSec * 1000).toLong(),
                    color = argb,
                    type = type,
                )
            }.sortedBy { it.timeMs }
                .also { LogBus.i(TAG, "弹幕拉取：$title E$episode -> ${it.size} 条") }
        } catch (e: Exception) {
            LogBus.w(TAG, "弹幕拉取失败：${e.message}")
            emptyList()
        }
    }

    private fun searchAnime(title: String): Long? {
        // 取标题前 20 字搜，避免过长标题搜不到
        val q = title.take(20).trim()
        if (q.isEmpty()) return null
        val req = Request.Builder()
            .url("https://api.dandanplay.net/api/v2/search/anime?keyword=${java.net.URLEncoder.encode(q, "UTF-8")}")
            .header("User-Agent", "movieshell/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val parsed = json.decodeFromString(SearchResp.serializer(), body)
            // 取第一个（最相关）
            return parsed.animes.firstOrNull()?.animeId
        }
    }

    private fun fetchComments(animeId: Long, episode: Int): List<Comment> {
        val req = Request.Builder()
            .url("https://api.dandanplay.net/api/v2/comment/$animeId/$episode")
            .header("User-Agent", "movieshell/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            return json.decodeFromString(CommentResp.serializer(), body).comments
        }
    }
}

/**
 * v0.54：弹幕开关与状态（持久化在 movieshell_prefs）。
 */
object DanmakuSettings {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_ENABLED = "danmaku_enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = p.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, on: Boolean) {
        _enabled.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        LogBus.i("Danmaku", "弹幕开关：$on")
    }
}
