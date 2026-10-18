package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.MusicApi.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.81.0：本地音乐 + 收藏歌曲持久化。
 *
 * 本地音乐：用户导入的本地音频文件（content URI 持久化，App 重启后仍可播，
 * 取决于系统是否保留 URI 授权——GetContent 授权一般在重启后失效，播不了就提示重新导入）。
 * 收藏：在在线搜索结果上点 ♥ 收藏。
 */
object MusicStore {
    private const val TAG = "MusicStore"
    private const val LOCAL_FILE = "music_local.json"
    private const val FAV_FILE = "music_fav.json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun readTracks(f: File): List<Track> = runCatching {
        if (!f.isFile) return emptyList()
        json.decodeFromString(ListSerializer(Track.serializer()), f.readText())
    }.onFailure { Log.w(TAG, "读取失败：${f.name}", it) }.getOrDefault(emptyList())

    private fun writeTracks(f: File, list: List<Track>) = runCatching {
        f.writeText(json.encodeToString(ListSerializer(Track.serializer()), list))
    }.onFailure { Log.w(TAG, "写入失败：${f.name}", it) }

    // ---- 本地音乐 ----

    suspend fun localList(context: Context): List<Track> = withContext(Dispatchers.IO) {
        readTracks(File(context.filesDir, LOCAL_FILE))
    }

    suspend fun addLocal(context: Context, track: Track) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, LOCAL_FILE)
        val cur = readTracks(f).toMutableList()
        if (cur.none { it.id == track.id }) {
            cur.add(0, track)
            writeTracks(f, cur)
        }
    }

    suspend fun removeLocal(context: Context, id: String) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, LOCAL_FILE)
        writeTracks(f, readTracks(f).filterNot { it.id == id })
    }

    // ---- 收藏 ----

    suspend fun favList(context: Context): List<Track> = withContext(Dispatchers.IO) {
        readTracks(File(context.filesDir, FAV_FILE))
    }

    suspend fun isFav(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        readTracks(File(context.filesDir, FAV_FILE)).any { it.id == id }
    }

    /** 切换收藏，返回切换后是否为收藏态。 */
    suspend fun toggleFav(context: Context, track: Track): Boolean = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, FAV_FILE)
        val cur = readTracks(f).toMutableList()
        val exists = cur.any { it.id == track.id }
        if (exists) cur.removeAll { it.id == track.id } else cur.add(0, track)
        writeTracks(f, cur)
        !exists
    }
}
