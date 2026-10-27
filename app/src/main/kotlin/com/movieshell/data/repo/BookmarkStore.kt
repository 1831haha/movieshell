package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** v0.89.0：单条书签。createdAt 既是排序键也是删除键。 */
@Serializable
data class Bookmark(
    val chapterIndex: Int,
    val charOffset: Int,
    val chapterTitle: String,
    val createdAt: Long,
)

/**
 * v0.89.0 小说书签：落在 filesDir/novel_bookmarks.json。
 * 单文件存 Map<novelId, List<Bookmark>>；全部 suspend，走 Dispatchers.IO。
 */
object BookmarkStore {

    private const val TAG = "BookmarkStore"
    private const val FILE_NAME = "novel_bookmarks.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), ListSerializer(Bookmark.serializer()))

    // v0.216.0（C9）：读-改-写串行化——连续快点两个书签时，后写不再用旧快照覆盖先写
    private val mutex = Mutex()

    private fun readAll(f: File): Map<String, List<Bookmark>> = runCatching {
        if (!f.isFile) return emptyMap()
        json.decodeFromString(mapSer, f.readText())
    }.onFailure {
        Log.w(TAG, "书签读取失败", it)
        // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
        AtomicFile.preserveCorrupt(f)
    }.getOrDefault(emptyMap())

    // v0.246.0：返回写盘成败——原来返回 Result 但调用方都不查，
    // 书签写失败重启后丢变更、用户无感知。改显式 Boolean。
    private fun writeAll(f: File, map: Map<String, List<Bookmark>>): Boolean = runCatching {
        // v0.170.0：原子写盘（临时文件 + fsync + 改名），写中途被杀不留截断文件
        AtomicFile.writeText(f, json.encodeToString(mapSer, map))
    }.onFailure { Log.w(TAG, "书签写入失败", it) }.getOrDefault(false)

    suspend fun list(context: Context, novelId: String): List<Bookmark> =
        withContext(Dispatchers.IO) {
            readAll(File(context.filesDir, FILE_NAME))[novelId].orEmpty()
                .sortedByDescending { it.createdAt }
        }

    /** 同章节去重：同一 chapterIndex 只保留最新一条。 */
    suspend fun add(context: Context, novelId: String, bookmark: Bookmark): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = File(context.filesDir, FILE_NAME)
                val cur = readAll(f).toMutableMap()
                cur[novelId] = (cur[novelId].orEmpty()
                    .filterNot { it.chapterIndex == bookmark.chapterIndex } + bookmark)
                    .sortedByDescending { it.createdAt }
                writeAll(f, cur)
            }
        }

    suspend fun remove(context: Context, novelId: String, createdAt: Long): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = File(context.filesDir, FILE_NAME)
                val cur = readAll(f).toMutableMap()
                cur[novelId] = cur[novelId].orEmpty().filterNot { it.createdAt == createdAt }
                writeAll(f, cur)
            }
        }
}
