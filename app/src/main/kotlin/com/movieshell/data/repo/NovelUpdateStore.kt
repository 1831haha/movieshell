package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** v0.84.0：追更检查结果——哪本书多了几章、什么时候查的。打开书后清除。 */
@Serializable
data class NovelUpdateInfo(
    val novelId: String = "",
    val newChapters: Int = 0,
    val checkedAt: Long = 0L,
)

/**
 * v0.84.0：追更标记持久化，落在 filesDir/novel_updates.json。
 * key=novelId。书架用它来打"＋N章"角标。
 */
object NovelUpdateStore {
    private const val TAG = "NovelUpdateStore"
    private const val FILE_NAME = "novel_updates.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), NovelUpdateInfo.serializer())

    private fun readAll(context: Context): Map<String, NovelUpdateInfo> {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.isFile) return emptyMap()
        return runCatching { json.decodeFromString(mapSer, f.readText()) }
            .onFailure { Log.w(TAG, "追更标记读取失败", it) }
            .getOrDefault(emptyMap())
    }

    private fun writeAll(context: Context, map: Map<String, NovelUpdateInfo>) {
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(json.encodeToString(mapSer, map))
        }.onFailure { Log.w(TAG, "追更标记写入失败", it) }
    }

    suspend fun getAll(context: Context): Map<String, NovelUpdateInfo> =
        withContext(Dispatchers.IO) { readAll(context) }

    suspend fun set(context: Context, novelId: String, newChapters: Int) =
        withContext(Dispatchers.IO) {
            if (newChapters <= 0) return@withContext
            val cur = readAll(context).toMutableMap()
            cur[novelId] = NovelUpdateInfo(
                novelId = novelId,
                newChapters = newChapters,
                checkedAt = System.currentTimeMillis(),
            )
            writeAll(context, cur)
        }

    /** 打开书后清除角标。 */
    suspend fun clear(context: Context, novelId: String) = withContext(Dispatchers.IO) {
        val cur = readAll(context).toMutableMap()
        if (cur.remove(novelId) != null) writeAll(context, cur)
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
            .onFailure { Log.w(TAG, "追更标记清空失败", it) }
    }
}
