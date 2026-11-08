package com.movieshell.data.novel

import android.content.Context
import com.movieshell.domain.model.ParagraphComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import java.io.File

/**
 * v1.1.10：段评结构化版 sidecar 缓存——novels/{id}.comments.json。
 *
 * 一书一文件，按章节下标存 {fetchedAt, comments}；TTL 24 小时，过期重拉。
 * 只看不写：这里只存站方拉回的评论，不调任何发表接口。
 * 文件与 NovelStore 的 novels/{id}.json 同目录、不同名，互不干扰。
 */
object ParagraphCommentStore {

    private const val DIR_NAME = "novels"
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    @Serializable
    data class CachedChapterComments(
        val fetchedAt: Long = 0L,
        val comments: List<ParagraphComment> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), CachedChapterComments.serializer())
    private val mutex = Mutex()

    private fun fileOf(context: Context, novelId: String): File {
        val dir = File(context.filesDir, DIR_NAME).also { it.mkdirs() }
        return File(dir, "$novelId.comments.json")
    }

    /** 命中且未过期返回评论列表；未命中、过期或文件损坏返回 null（调用方重新拉取）。 */
    suspend fun load(context: Context, novelId: String, chapterIndex: Int): List<ParagraphComment>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = fileOf(context, novelId)
                if (!f.exists()) return@withLock null
                val map = runCatching { json.decodeFromString(mapSer, f.readText()) }.getOrNull()
                    ?: return@withLock null
                val entry = map[chapterIndex.toString()] ?: return@withLock null
                if (System.currentTimeMillis() - entry.fetchedAt > TTL_MS) return@withLock null
                entry.comments
            }
        }

    /** 写盘（读-改-写串行化；坏文件直接重建，不炸）。 */
    suspend fun save(context: Context, novelId: String, chapterIndex: Int, comments: List<ParagraphComment>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = fileOf(context, novelId)
                val map = runCatching { json.decodeFromString(mapSer, f.readText()) }
                    .getOrDefault(emptyMap()).toMutableMap()
                map[chapterIndex.toString()] = CachedChapterComments(System.currentTimeMillis(), comments)
                runCatching { f.writeText(json.encodeToString(mapSer, map)) }
            }
        }
    }

    /**
     * 段评 JSON 数组解析（规则求值结果 → 模型列表）。
     * 返回 null 表示求值失败或串不是合法 JSON 数组（调用方不缓存，下次重试）；
     * 合法空数组返回 emptyList（可缓存——该章确实无评论）。
     * paraIndex < 1 的伪段（如晋江 -2）直接丢掉；
     * likes/total 可能是字符串数字，optInt 对数字与数字字符串都认。
     */
    fun parseJson(jsonStr: String): List<ParagraphComment>? {
        if (jsonStr.isBlank()) return null
        return runCatching {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val idx = o.optInt("paraIndex", 0)
                if (idx < 1) return@mapNotNull null
                ParagraphComment(
                    paraIndex = idx,
                    author = o.optString("author"),
                    text = o.optString("text"),
                    likes = o.optInt("likes", 0),
                    time = o.optString("time"),
                    total = o.optInt("total", 0),
                )
            }
        }.getOrNull()
    }
}
