package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 收藏条目。联合唯一键：(sourceName, vodId)，同一 vodId 在不同来源下可各收一条。
 */
@Serializable
data class FavoriteItem(
    val vodId: String = "",
    val name: String = "",
    val pic: String = "",
    val sourceName: String = "",
    val savedAt: Long = 0L,
    /** v0.5.1：订阅子站点的 key（"sub:…"），回跳详情时恢复站点上下文；本地规则收藏为空 */
    val subSiteKey: String = ""
)

/**
 * 追剧收藏：落在 filesDir/favorites.json（JSON 数组）。
 *
 * - 所有函数都是 suspend，IO 走 Dispatchers.IO；
 * - add/remove 幂等：add 已存在则刷新 savedAt 并置顶，remove 不存在也返回 true；
 * - 读写异常吞掉打日志，不向上传播崩溃；文件损坏时当作空列表。
 */
object FavoriteStore {

    private const val TAG = "FavoriteStore"
    private const val FILE_NAME = "favorites.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** 收藏列表，按收藏时间倒序。 */
    suspend fun list(context: Context): List<FavoriteItem> = withContext(Dispatchers.IO) {
        readAll(context).sortedByDescending { it.savedAt }
    }

    suspend fun isFavorite(context: Context, vodId: String, sourceName: String): Boolean =
        withContext(Dispatchers.IO) {
            readAll(context).any { it.vodId == vodId && it.sourceName == sourceName }
        }

    /** 幂等添加：已存在则刷新 savedAt 并移到最前。 */
    suspend fun add(context: Context, item: FavoriteItem): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val fresh = item.copy(savedAt = System.currentTimeMillis())
                val rest = readAll(context)
                    .filterNot { it.vodId == fresh.vodId && it.sourceName == fresh.sourceName }
                writeAll(context, listOf(fresh) + rest)
                true
            }.onFailure { Log.w(TAG, "收藏写入失败", it) }.getOrDefault(false)
        }

    /** 幂等删除：不存在也返回 true。 */
    suspend fun remove(context: Context, vodId: String, sourceName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val rest = readAll(context)
                    .filterNot { it.vodId == vodId && it.sourceName == sourceName }
                writeAll(context, rest)
                true
            }.onFailure { Log.w(TAG, "取消收藏写入失败", it) }.getOrDefault(false)
        }

    private fun fileOf(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readAll(context: Context): List<FavoriteItem> {
        val f = fileOf(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(FavoriteItem.serializer()), f.readText())
        }.onFailure {
            Log.w(TAG, "favorites.json 解析失败，已按空列表处理", it)
        }.getOrDefault(emptyList())
    }

    private fun writeAll(context: Context, items: List<FavoriteItem>) {
        fileOf(context).writeText(
            json.encodeToString(ListSerializer(FavoriteItem.serializer()), items)
        )
    }
}
