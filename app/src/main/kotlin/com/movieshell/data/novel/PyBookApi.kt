package com.movieshell.data.novel

import android.util.Log
import com.movieshell.data.repo.LogBus
import com.movieshell.data.source.ChaquopyEngine
import com.movieshell.data.source.PyEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * v0.64.0 PY 书源客户端：经 book_call 调书源规则文件的书源方法。
 *
 * 书源规则约定（.py 文件里的类）：
 * - bookSearch(key)      -> {"list": [{"book_id","book_name","author","cover"}]}
 * - bookDetail(book_id)   -> {"book_name","author","intro","cover",
 *                             "chapters": [{"title","url"}]}
 * - bookChapter(url)      -> {"content": "正文"}
 */
class PyBookApi(private val engine: PyEngine = ChaquopyEngine) {

    private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 书籍信息（bookDetail 返回）。 */
    data class BookDetail(
        val name: String = "",
        val author: String? = null,
        val intro: String = "",
        val cover: String? = null,
        val chapters: List<ChapterMeta> = emptyList(),
    )

    /** 解开 {"ok":true,"data":{...}} 信封；失败返回 null。 */
    private fun envelope(text: String): JsonObject? = runCatching {
        val root = jsonLenient.parseToJsonElement(text).jsonObject
        if ((root["ok"] as? JsonPrimitive)?.contentOrNull != "true") {
            val err = root["error"]?.toString() ?: "未知错误"
            Log.w(TAG, "PY 书源返回失败: $err")
            LogBus.e(TAG, "PY 书源失败: $err")
            return null
        }
        (root["data"] as? JsonObject)
    }.getOrNull()

    private fun JsonObject.opt(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 搜书。 */
    suspend fun search(rulePath: String, key: String): List<NovelSearchResult> {
        val args = buildJsonObject { put("key", key) }.toString()
        val data = envelope(engine.bookInvoke(rulePath, "bookSearch", args)) ?: return emptyList()
        return (data["list"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.opt("book_name")
            val id = o.opt("book_id")
            if (name.isBlank() || id.isBlank()) return@mapNotNull null
            NovelSearchResult(
                remoteId = id,
                title = name,
                author = o.opt("author").ifBlank { null },
                cover = o.opt("cover").ifBlank { null },
            )
        }
    }

    /** 取书详情（含目录）。 */
    suspend fun detail(rulePath: String, bookId: String): BookDetail? {
        val args = buildJsonObject { put("book_id", bookId) }.toString()
        val data = envelope(engine.bookInvoke(rulePath, "bookDetail", args)) ?: return null
        val chapters = (data["chapters"] as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
            val o = e as? JsonObject ?: return@mapIndexedNotNull null
            val title = o.opt("title")
            val url = o.opt("url")
            if (title.isBlank() || url.isBlank()) return@mapIndexedNotNull null
            ChapterMeta(index = i, title = title, url = url)
        }
        return BookDetail(
            name = data.opt("book_name"),
            author = data.opt("author").ifBlank { null },
            intro = data.opt("intro"),
            cover = data.opt("cover").ifBlank { null },
            chapters = chapters,
        )
    }

    /** 取单章正文。 */
    suspend fun chapter(rulePath: String, url: String): String {
        val args = buildJsonObject { put("url", url) }.toString()
        val data = envelope(engine.bookInvoke(rulePath, "bookChapter", args)) ?: return ""
        return data.opt("content")
    }

    companion object {
        private const val TAG = "PyBookApi"
    }
}
