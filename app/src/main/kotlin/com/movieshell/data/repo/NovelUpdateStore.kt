package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            .onFailure {
                Log.w(TAG, "追更标记读取失败", it)
                // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
                AtomicFile.preserveCorrupt(f)
            }
            .getOrDefault(emptyMap())
    }

    // v0.211.0 审计修复：手动检查收尾与后台 Worker 原来无锁
    // 读-改-写，后写者用旧快照覆盖先写者的角标；set/clear 同锁串行化。
    private val storeMutex = Mutex()

    private fun writeAll(context: Context, map: Map<String, NovelUpdateInfo>) {
        runCatching {
            // v0.170.0：原子写盘
            AtomicFile.writeText(
                File(context.filesDir, FILE_NAME),
                json.encodeToString(mapSer, map),
            )
        }.onFailure { Log.w(TAG, "追更标记写入失败", it) }
    }

    suspend fun getAll(context: Context): Map<String, NovelUpdateInfo> =
        withContext(Dispatchers.IO) { readAll(context) }

    /** v0.211.0 审计修复：角标原来直接覆盖——第一轮 +3 未读、
     *  第二轮又多 2 章时角标被写成 2，越查越少。set 语义改为
     *  累加未读存量（读过书后 clear 清零，下一轮从 0 起算）；
     *  调用方传的是本轮新增数，不是总量。 */
    suspend fun set(context: Context, novelId: String, newChapters: Int) =
        withContext(Dispatchers.IO) {
            storeMutex.withLock {
                if (newChapters <= 0) return@withLock
                val cur = readAll(context).toMutableMap()
                val total = (cur[novelId]?.newChapters ?: 0) + newChapters
                cur[novelId] = NovelUpdateInfo(
                    novelId = novelId,
                    newChapters = total,
                    checkedAt = System.currentTimeMillis(),
                )
                writeAll(context, cur)
            }
        }

    /** 打开书后清除角标。 */
    suspend fun clear(context: Context, novelId: String) = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val cur = readAll(context).toMutableMap()
            if (cur.remove(novelId) != null) writeAll(context, cur)
        }
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
            .onFailure { Log.w(TAG, "追更标记清空失败", it) }
    }
}
