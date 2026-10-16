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
     *
     * v0.55：智能匹配——标题清洗（去集数/特殊符号/括号内容）+ 多关键词回退；
     * 结果去重（同文本 3 秒内只留一条）+ 密度控制（每分钟最多 60 条）。
     */
    suspend fun fetch(title: String, episode: Int = 1): List<Danmaku> = withContext(Dispatchers.IO) {
        try {
            // v0.55：先查磁盘缓存
            val cacheKey = "${title.take(30)}_E$episode"
            loadCache(cacheKey)?.let {
                LogBus.i(TAG, "弹幕磁盘缓存命中：$title E$episode -> ${it.size} 条")
                return@withContext it
            }
            val animeId = searchAnimeSmart(title) ?: return@withContext emptyList()
            val comments = fetchComments(animeId, episode)
            val result = comments.mapNotNull { c ->
                val parts = c.p.split(",")
                if (parts.size < 3 || c.m.isBlank()) return@mapNotNull null
                val timeSec = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val mode = parts[1].toIntOrNull() ?: 1
                val colorInt = parts[2].toIntOrNull() ?: 16777215
                val type = when (mode) {
                    4 -> Danmaku.TYPE_BOTTOM
                    5 -> Danmaku.TYPE_TOP
                    else -> Danmaku.TYPE_SCROLL
                }
                val argb = 0xFF000000.toInt() or (colorInt and 0xFFFFFF)
                Danmaku(
                    text = c.m.take(60),
                    timeMs = (timeSec * 1000).toLong(),
                    color = argb,
                    type = type,
                )
            }
                .sortedBy { it.timeMs }
                .let { dedup(it) }      // v0.55：去重
                .let { limitDensity(it) } // v0.55：密度控制
            // v0.55：写磁盘缓存
            if (result.isNotEmpty()) saveCache(cacheKey, result)
            LogBus.i(TAG, "弹幕拉取：$title E$episode -> ${result.size} 条（去重限流后）")
            result
        } catch (e: Exception) {
            LogBus.w(TAG, "弹幕拉取失败：${e.message}")
            emptyList()
        }
    }

    /** v0.55：标题清洗 + 多关键词回退搜索 */
    private fun searchAnimeSmart(title: String): Long? {
        val candidates = buildList {
            // 1. 原标题前 20 字
            add(title.take(20).trim())
            // 2. 去括号内容（"斗罗大陆（重制版）" -> "斗罗大陆"）
            val noBracket = title.replace(Regex("[（(\\[].*?[）)\\]]"), "").trim().take(20)
            if (noBracket.isNotEmpty() && noBracket != title.take(20).trim()) add(noBracket)
            // 3. 去集数/季数（"凡人修仙传 第2季" -> "凡人修仙传"）
            val noEp = title.replace(Regex("\\s*第\\s*[一二三四五六七八九十0-9]+\\s*[季部]"), "")
                .replace(Regex("\\s*S\\d+", RegexOption.IGNORE_CASE), "")
                .trim().take(20)
            if (noEp.isNotEmpty() && noEp != title.take(20).trim()) add(noEp)
        }.distinct().filter { it.isNotEmpty() }
        for (q in candidates) {
            val id = searchAnime(q)
            if (id != null) {
                if (q != candidates[0]) LogBus.i(TAG, "弹幕标题回退匹配：$q")
                return id
            }
        }
        return null
    }

    /** v0.55：去重——同文本 3 秒内只留第一条 */
    private fun dedup(list: List<Danmaku>): List<Danmaku> {
        if (list.size < 2) return list
        val result = ArrayList<Danmaku>(list.size)
        val lastSeen = mutableMapOf<String, Long>()
        for (d in list) {
            val last = lastSeen[d.text]
            if (last == null || d.timeMs - last > 3000) {
                result.add(d)
                lastSeen[d.text] = d.timeMs
            }
        }
        // 防内存爆炸：只记最近 500 条文本
        if (lastSeen.size > 500) lastSeen.clear()
        return result
    }

    /** v0.55：密度控制——每分钟最多 60 条，超了均匀抽稀 */
    private fun limitDensity(list: List<Danmaku>): List<Danmaku> {
        if (list.isEmpty()) return list
        val result = ArrayList<Danmaku>(list.size)
        var windowStart = 0L
        var windowCount = 0
        for (d in list) {
            val window = d.timeMs / 60_000
            if (window != windowStart) {
                windowStart = window
                windowCount = 0
            }
            if (windowCount < 60) {
                result.add(d)
                windowCount++
            }
            // 超了就丢（按时间排序，丢的是后面的，保留前面的）
        }
        return result
    }

    // v0.55：弹幕磁盘缓存（filesDir/danmaku_cache，按标题+集数存 7 天）
    private const val CACHE_VALID_MS = 7L * 24 * 60 * 60 * 1000
    private var appContext: Context? = null
    fun initCache(context: Context) { appContext = context.applicationContext }

    private fun cacheFile(key: String): java.io.File? {
        val ctx = appContext ?: return null
        val dir = java.io.File(ctx.filesDir, "danmaku_cache").also { it.mkdirs() }
        val safe = key.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(60)
        return java.io.File(dir, "$safe.json")
    }

    private fun loadCache(key: String): List<Danmaku>? {
        val f = cacheFile(key) ?: return null
        return runCatching {
            val text = f.readText()
            val e = json.decodeFromString(CacheEntry.serializer(), text)
            if (System.currentTimeMillis() - e.savedAt > CACHE_VALID_MS) return null
            if (e.items.isEmpty()) return null
            e.items.map { Danmaku(it.text, it.timeMs, it.color, it.type) }
        }.getOrNull()
    }

    private fun saveCache(key: String, list: List<Danmaku>) {
        val f = cacheFile(key) ?: return
        runCatching {
            // 只存前 500 条，文件别太大
            val e = CacheEntry(
                savedAt = System.currentTimeMillis(),
                items = list.take(500).map { CacheItem(it.text, it.timeMs, it.color, it.type) },
            )
            f.writeText(json.encodeToString(CacheEntry.serializer(), e))
        }
    }

    @Serializable
    private data class CacheEntry(val savedAt: Long = 0L, val items: List<CacheItem> = emptyList())
    @Serializable
    private data class CacheItem(val text: String, val timeMs: Long, val color: Int, val type: Int)

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
