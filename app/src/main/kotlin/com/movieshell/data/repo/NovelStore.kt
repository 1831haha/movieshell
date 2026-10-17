package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.model.ShelfTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.35.0 小说书架：落在 filesDir/novels/ 下。
 * - index.json：全部 Novel 元信息（数组）
 * - {id}.json：该书的章节列表
 *
 * 照 FavoriteStore 的风格：suspend + Dispatchers.IO，异常吞掉打日志。
 */
object NovelStore {

    private const val TAG = "NovelStore"
    private const val DIR_NAME = "novels"
    private const val INDEX_NAME = "index.json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun dirOf(context: Context): File = File(context.filesDir, DIR_NAME).also { it.mkdirs() }
    private fun indexFile(context: Context): File = File(dirOf(context), INDEX_NAME)
    private fun chaptersFile(context: Context, novelId: String): File =
        File(dirOf(context), "$novelId.json")

    private fun readIndex(context: Context): List<Novel> {
        val f = indexFile(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Novel.serializer()), f.readText())
        }.onFailure { Log.w(TAG, "书架索引读取失败", it) }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, list: List<Novel>) {
        runCatching {
            indexFile(context).writeText(json.encodeToString(ListSerializer(Novel.serializer()), list))
        }.onFailure { Log.w(TAG, "书架索引写入失败", it) }
    }

    /** 全部书，按加入时间倒序。 */
    suspend fun list(context: Context): List<Novel> = withContext(Dispatchers.IO) {
        readIndex(context).sortedByDescending { it.addedAt }
    }

    suspend fun get(context: Context, id: String): Novel? = withContext(Dispatchers.IO) {
        readIndex(context).firstOrNull { it.id == id }
    }

    /** 幂等添加：同 id 已存在则保留进度只更新元信息。 */
    suspend fun addNovel(context: Context, novel: Novel, chapters: List<Chapter>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val fixed = chapters.mapIndexed { i, c ->
                    c.copy(novelId = novel.id, index = i)
                }
                chaptersFile(context, novel.id).writeText(
                    json.encodeToString(ListSerializer(Chapter.serializer()), fixed),
                )
                val old = readIndex(context).firstOrNull { it.id == novel.id }
                val merged = novel.copy(
                    totalChapters = fixed.size,
                    lastReadChapter = old?.lastReadChapter ?: 0,
                    addedAt = old?.addedAt ?: System.currentTimeMillis(),
                )
                val rest = readIndex(context).filterNot { it.id == novel.id }
                writeIndex(context, listOf(merged) + rest)
                true
            }.onFailure { Log.w(TAG, "小说导入失败", it) }.getOrDefault(false)
        }

    suspend fun removeNovel(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            writeIndex(context, readIndex(context).filterNot { it.id == id })
            chaptersFile(context, id).delete()
            true
        }.onFailure { Log.w(TAG, "删除小说失败", it) }.getOrDefault(false)
    }

    suspend fun chapters(context: Context, novelId: String): List<Chapter> =
        withContext(Dispatchers.IO) {
            val f = chaptersFile(context, novelId)
            if (!f.isFile) return@withContext emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(Chapter.serializer()), f.readText())
            }.onFailure { Log.w(TAG, "章节读取失败", it) }.getOrDefault(emptyList())
        }

    suspend fun chapter(context: Context, novelId: String, index: Int): Chapter? =
        withContext(Dispatchers.IO) {
            chapters(context, novelId).getOrNull(index)
        }

    /** 更新读到第几章（书架分组用）。 */
    suspend fun updateProgress(context: Context, novelId: String, chapterIndex: Int) =
        withContext(Dispatchers.IO) {
            runCatching {
                val all = readIndex(context)
                writeIndex(context, all.map {
                    if (it.id == novelId) it.copy(lastReadChapter = chapterIndex) else it
                })
            }.onFailure { Log.w(TAG, "阅读进度更新失败", it) }
        }

    /** 更新单章正文（在线书源按需拉取后缓存）。 */
    suspend fun updateChapterContent(context: Context, novelId: String, index: Int, content: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = chaptersFile(context, novelId)
                if (!f.isFile) return@runCatching
                val list = json.decodeFromString(ListSerializer(Chapter.serializer()), f.readText())
                val updated = list.map { if (it.index == index) it.copy(content = content) else it }
                f.writeText(json.encodeToString(ListSerializer(Chapter.serializer()), updated))
            }.onFailure { Log.w(TAG, "章节正文缓存失败", it) }
        }

    /** 书架分组：在追 / 想看 / 已看完。 */
    suspend fun listShelves(context: Context): Map<ShelfTab, List<Novel>> =
        withContext(Dispatchers.IO) {
            val all = list(context)
            mapOf(
                ShelfTab.READING to all.filter { it.lastReadChapter > 0 && it.lastReadChapter < it.totalChapters - 1 },
                ShelfTab.WANT to all.filter { it.lastReadChapter == 0 },
                ShelfTab.FINISHED to all.filter { it.lastReadChapter > 0 && it.totalChapters > 0 && it.lastReadChapter >= it.totalChapters - 1 },
            )
        }
}
