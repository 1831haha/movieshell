package com.movieshell.data.source

import com.movieshell.core.net.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 豆瓣真实短评（v1.1.21）。
 *
 * 两步走：suggest 按片名找豆瓣条目 id，interests 按 id 取短评。
 * 豆瓣没有公开接口，这两条都是移动端页面在用的未公开接口，无需登录、
 * 无需 Cookie；本机出口 IP 下短评接口会间歇 400（疑似按 IP 限流），
 * 所以失败一律吞成空结果，调用方只显示诚实空态，不编造、不崩溃。
 */
object DoubanReviews {

    data class DoubanReview(
        val userName: String,
        val avatarUrl: String,
        val stars: Int,
        val time: String,
        val text: String,
        val voteCount: Int,
    )

    private const val TIMEOUT_MS = 10_000L
    private const val MAX_COUNT = 10
    private const val DOUBAN_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val REFERER = "https://m.douban.com/"
    private const val SUGGEST_URL = "https://movie.douban.com/j/subject_suggest?q="
    private const val INTERESTS_URL = "https://m.douban.com/rexxar/api/v2/movie/"

    private val idCache = ConcurrentHashMap<String, IdEntry>()
    private val reviewsCache = ConcurrentHashMap<String, ReviewsEntry>()

    /**
     * v1.3.33 B-P2-5：缓存上限——片名→豆瓣 id 变化不频繁，TTL 24 小时/上限 500；
     * 短评会更新，TTL 10 分钟/上限 300。TTL 与条目数一起做：只有 TTL 不够，
     * 过期条目没人再访问就永远留在 Map 里。
     */
    private const val ID_TTL_MS = 24L * 60 * 60 * 1000
    private const val ID_MAX_ENTRIES = 500
    private const val REVIEWS_TTL_MS = 10L * 60 * 1000
    private const val REVIEWS_MAX_ENTRIES = 300

    private data class IdEntry(val id: String, val savedAt: Long)
    private data class ReviewsEntry(val list: List<DoubanReview>, val savedAt: Long)

    /** 是否过期（savedAt 距 now 超过 ttl）。纯函数，供单测。 */
    internal fun isExpired(savedAt: Long, now: Long, ttlMs: Long): Boolean = now - savedAt > ttlMs

    /**
     * 有界淘汰：超上限时返回最旧一条的 key（按 savedAt），否则 null。
     * 纯函数，供单测。
     */
    internal fun <T> oldestKeyIfOver(map: Map<String, T>, max: Int, savedAt: (T) -> Long): String? {
        if (map.size <= max) return null
        return map.entries.minByOrNull { savedAt(it.value) }?.key
    }

    /** 有界写入——超上限时删最旧一条；写入是低频操作（每新片一次），单次淘汰足够。 */
    private fun <T> putBounded(
        map: ConcurrentHashMap<String, T>, key: String, value: T, max: Int, savedAt: (T) -> Long,
    ) {
        map[key] = value
        oldestKeyIfOver(map, max, savedAt)?.let { map.remove(it) }
    }

    private val seasonSuffix = Regex(
        """\s*(第[一二三四五六七八九十0-9]+季|第[一二三四五六七八九十0-9]+部|S[0-9]+|Season\s*[0-9]+)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** 片名直取短评：先完整片名搜，搜不到再去掉季后缀搜一遍；失败回空列表。 */
    suspend fun reviewsFor(title: String): List<DoubanReview> {
        val query = title.trim()
        if (query.isBlank()) return emptyList()
        var id = suggest(query)
        val stripped = query.replace(seasonSuffix, "").trim()
        if (id == null && stripped.isNotEmpty() && stripped != query) {
            id = suggest(stripped)
        }
        if (id == null) return emptyList()
        return interests(id)
    }

    /** 按片名找豆瓣条目 id：多条结果优先标题完全一致的，否则取第一条。 */
    suspend fun suggest(title: String): String? {
        val query = title.trim()
        if (query.isBlank()) return null
        idCache[query]?.let { e ->
            if (!isExpired(e.savedAt, System.currentTimeMillis(), ID_TTL_MS)) return e.id
            idCache.remove(query, e)
        }
        val id = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                parseSuggest(getText(SUGGEST_URL + URLEncoder.encode(query, "UTF-8")), query)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (id != null) putBounded(idCache, query, IdEntry(id, System.currentTimeMillis()), ID_MAX_ENTRIES) { it.savedAt }
        return id
    }

    /** 按条目 id 取短评（第一页，最多 10 条）；失败回空列表。 */
    suspend fun interests(id: String): List<DoubanReview> {
        if (id.isBlank()) return emptyList()
        reviewsCache[id]?.let { e ->
            if (!isExpired(e.savedAt, System.currentTimeMillis(), REVIEWS_TTL_MS)) return e.list
            reviewsCache.remove(id, e)
        }
        val list = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                parseInterests(getText(INTERESTS_URL + id + "/interests?count=$MAX_COUNT&status=P"))
            } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (list.isNotEmpty()) putBounded(reviewsCache, id, ReviewsEntry(list, System.currentTimeMillis()), REVIEWS_MAX_ENTRIES) { it.savedAt }
        return list
    }

    private fun parseSuggest(json: String, query: String): String? {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        var first: String? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            if (id.isBlank()) continue
            if (first == null) first = id
            val title = obj.optString("title").trim()
            val sub = obj.optString("sub_title").trim()
            if (title.equals(query, ignoreCase = true) ||
                sub.equals(query, ignoreCase = true)
            ) {
                return id
            }
        }
        return first
    }

    private fun parseInterests(json: String): List<DoubanReview> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("interests") ?: return emptyList()
        val out = ArrayList<DoubanReview>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val comment = obj.optString("comment").trim()
            if (comment.isBlank()) continue
            val user = obj.optJSONObject("user")
            val rating = obj.optJSONObject("rating")
            out += DoubanReview(
                userName = user?.optString("name").orEmpty().ifBlank { "豆瓣用户" },
                avatarUrl = user?.optString("avatar").orEmpty(),
                stars = rating?.optInt("value", 0) ?: 0,
                time = obj.optString("create_time").orEmpty(),
                text = comment,
                voteCount = obj.optInt("vote_count", 0),
            )
        }
        return out
    }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val call = HttpClient.defaultClient().newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DOUBAN_UA)
                .header("Referer", REFERER)
                .build(),
        )
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val job = currentCoroutineContext()[Job]
            if (job?.isCancelled == true) currentCoroutineContext().ensureActive()
            throw e
        }
    }
}
