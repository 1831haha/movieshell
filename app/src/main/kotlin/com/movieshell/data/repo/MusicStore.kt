package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.MusicApi.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** v0.84.0：自建歌单。整首 Track 存进歌单（在线歌曲不依赖搜索结果缓存）。 */
@Serializable
data class MusicPlaylist(
    val id: String = "",
    val name: String = "",
    val tracks: List<Track> = emptyList(),
    val createdAt: Long = 0L,
)

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

    // ---- 自建歌单（v0.84.0） ----

    private const val PLAYLIST_FILE = "music_playlists.json"

    private fun readPlaylists(f: File): List<MusicPlaylist> = runCatching {
        if (!f.isFile) return emptyList()
        json.decodeFromString(ListSerializer(MusicPlaylist.serializer()), f.readText())
    }.onFailure { Log.w(TAG, "歌单读取失败", it) }.getOrDefault(emptyList())

    private fun writePlaylists(f: File, list: List<MusicPlaylist>) = runCatching {
        f.writeText(json.encodeToString(ListSerializer(MusicPlaylist.serializer()), list))
    }.onFailure { Log.w(TAG, "歌单写入失败", it) }

    suspend fun playlistList(context: Context): List<MusicPlaylist> = withContext(Dispatchers.IO) {
        readPlaylists(File(context.filesDir, PLAYLIST_FILE))
            .sortedByDescending { it.createdAt }
    }

    suspend fun getPlaylist(context: Context, id: String): MusicPlaylist? = withContext(Dispatchers.IO) {
        readPlaylists(File(context.filesDir, PLAYLIST_FILE)).firstOrNull { it.id == id }
    }

    suspend fun createPlaylist(context: Context, name: String): MusicPlaylist? =
        withContext(Dispatchers.IO) {
            val nm = name.trim()
            if (nm.isEmpty()) return@withContext null
            val f = File(context.filesDir, PLAYLIST_FILE)
            val cur = readPlaylists(f).toMutableList()
            if (cur.any { it.name == nm }) return@withContext null // 重名不建
            val pl = MusicPlaylist(
                id = "pl_${System.currentTimeMillis()}",
                name = nm,
                createdAt = System.currentTimeMillis(),
            )
            cur.add(pl)
            writePlaylists(f, cur)
            pl
        }

    suspend fun renamePlaylist(context: Context, id: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            val nm = name.trim()
            if (nm.isEmpty()) return@withContext false
            val f = File(context.filesDir, PLAYLIST_FILE)
            val cur = readPlaylists(f)
            if (cur.none { it.id == id } || cur.any { it.name == nm && it.id != id }) {
                return@withContext false
            }
            writePlaylists(f, cur.map { if (it.id == id) it.copy(name = nm) else it })
            true
        }

    suspend fun deletePlaylist(context: Context, id: String) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, PLAYLIST_FILE)
        writePlaylists(f, readPlaylists(f).filterNot { it.id == id })
    }

    /** 往歌单加歌；已在则返回 false。 */
    suspend fun addToPlaylist(context: Context, playlistId: String, track: Track): Boolean =
        withContext(Dispatchers.IO) {
            val f = File(context.filesDir, PLAYLIST_FILE)
            val cur = readPlaylists(f)
            val pl = cur.firstOrNull { it.id == playlistId } ?: return@withContext false
            if (pl.tracks.any { it.id == track.id }) return@withContext false
            writePlaylists(
                f,
                cur.map { if (it.id == playlistId) it.copy(tracks = it.tracks + track) else it },
            )
            true
        }

    suspend fun removeFromPlaylist(context: Context, playlistId: String, trackId: String) =
        withContext(Dispatchers.IO) {
            val f = File(context.filesDir, PLAYLIST_FILE)
            val cur = readPlaylists(f)
            writePlaylists(
                f,
                cur.map {
                    if (it.id == playlistId) it.copy(tracks = it.tracks.filterNot { t -> t.id == trackId })
                    else it
                },
            )
        }
}
