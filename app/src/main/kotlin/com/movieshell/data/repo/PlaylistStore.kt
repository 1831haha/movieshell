package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 收藏片单。 */
@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val createdAt: Long = 0L,
)

/**
 * v0.69.0 收藏片单：落在 filesDir/playlists.json（JSON 数组）。
 *
 * - 片单增删改；删除片单时其下条目回到未分组（不删收藏本身）；
 * - FavoriteItem.playlistId 关联片单，null/空 = 未分组。
 */
object PlaylistStore {

    private const val TAG = "PlaylistStore"
    private const val FILE_NAME = "playlists.json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readAll(context: Context): List<Playlist> = runCatching {
        val f = file(context)
        if (!f.isFile) return emptyList()
        json.decodeFromString(ListSerializer(Playlist.serializer()), f.readText())
    }.onFailure { Log.w(TAG, "读取片单失败", it) }.getOrDefault(emptyList())

    private fun writeAll(context: Context, list: List<Playlist>) = runCatching {
        file(context).writeText(json.encodeToString(ListSerializer(Playlist.serializer()), list))
    }.onFailure { Log.w(TAG, "写入片单失败", it) }

    suspend fun list(context: Context): List<Playlist> = withContext(Dispatchers.IO) {
        readAll(context).sortedBy { it.createdAt }
    }

    /** 新建片单，返回新建的片单（名空/重复返回 null）。 */
    suspend fun create(context: Context, name: String): Playlist? = withContext(Dispatchers.IO) {
        val n = name.trim()
        if (n.isEmpty()) return@withContext null
        val all = readAll(context)
        if (all.any { it.name == n }) return@withContext null
        val p = Playlist(id = UUID.randomUUID().toString(), name = n, createdAt = System.currentTimeMillis())
        writeAll(context, all + p)
        p
    }

    suspend fun rename(context: Context, id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val n = name.trim()
        if (n.isEmpty()) return@withContext false
        val all = readAll(context)
        if (all.any { it.name == n && it.id != id }) return@withContext false
        writeAll(context, all.map { if (it.id == id) it.copy(name = n) else it })
        true
    }

    /** 删除片单：其下收藏条目回到未分组。 */
    suspend fun delete(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        writeAll(context, readAll(context).filterNot { it.id == id })
        // 条目回未分组
        FavoriteStore.clearPlaylist(context, id)
        true
    }
}
