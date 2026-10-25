package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.MusicApi.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * v0.186.0：本地、收藏、歌单三文件共用同一把锁——同一 store 内任何读改写全程串行。
     * v0.216.0（C4）：读坏标记与 MusicSourceStore 同款——任一文件解析失败即置 true，
     * 此后一切写盘拒绝，直到用户点“重新开始”清掉损坏文件，防止空表覆盖旧数据。
     */
    private val mutex = Mutex()
    private var corruptLoaded = false

    /** 任一音乐数据文件的损坏备份是否还在，供 UI 横幅判断。 */
    suspend fun hasCorruptBackup(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            listOf(LOCAL_FILE, FAV_FILE, PLAYLIST_FILE).any {
                File(context.filesDir, "$it.corrupt.bak").isFile
            }
        }
    }

    /** 用户点的“重新开始”：删掉损坏的数据文件与备份，标记清零（旧数据不可恢复，变显式放弃）。 */
    suspend fun clearCorrupt(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                listOf(LOCAL_FILE, FAV_FILE, PLAYLIST_FILE).forEach {
                    File(context.filesDir, it).delete()
                    File(context.filesDir, "$it.corrupt.bak").delete()
                }
                corruptLoaded = false
                true
            }.getOrDefault(false)
        }
    }

    private fun readTracks(f: File): List<Track> = runCatching {
        if (!f.isFile) return emptyList()
        json.decodeFromString(ListSerializer(Track.serializer()), f.readText())
    }.onFailure {
        Log.w(TAG, "读取失败：${f.name}", it)
        // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
        AtomicFile.preserveCorrupt(f)
        corruptLoaded = true
    }.getOrDefault(emptyList())

    private fun writeTracks(f: File, list: List<Track>): Boolean {
        if (corruptLoaded) {
            LogBus.w(TAG, "音乐数据读坏未处理，拒绝写盘覆盖：${f.name}")
            return false
        }
        val ok = runCatching {
            // v0.170.0：原子写盘
            AtomicFile.writeText(f, json.encodeToString(ListSerializer(Track.serializer()), list))
        }.onFailure { Log.w(TAG, "写入失败：${f.name}", it) }.getOrDefault(false)
        if (!ok) LogBus.w(TAG, "写入失败：${f.name}")
        return ok
    }

    // ---- 本地音乐 ----

    suspend fun localList(context: Context): List<Track> = mutex.withLock {
        withContext(Dispatchers.IO) {
            readTracks(File(context.filesDir, LOCAL_FILE))
        }
    }

    /**
     * v0.188.0 M23：写盘成败如实返回（已存在不写盘也算成功，调用方不再谎报已导入）。
     * 无操作分支返回 true，只有真实写盘失败才返回 false。
     */
    suspend fun addLocal(context: Context, track: Track): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = File(context.filesDir, LOCAL_FILE)
            val cur = readTracks(f).toMutableList()
            if (cur.none { it.id == track.id }) {
                cur.add(0, track)
                writeTracks(f, cur)
            } else {
                true
            }
        }
    }

    suspend fun removeLocal(context: Context, id: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = File(context.filesDir, LOCAL_FILE)
            writeTracks(f, readTracks(f).filterNot { it.id == id })
        }
    }

    /**
     * v0.188.0 M23：删歌四处联动——本地、收藏、全部歌单内嵌 tracks、播放历史
     * （历史经 MusicHistoryStore.removePlay，放在本 store 锁外，避免两锁交错）。
     * 删歌单内单首（removeFromPlaylist）不是删歌，不走这里。
     * 返回值只覆盖本地、收藏、歌单三处写盘（历史写盘失败不翻转返回值，尽力而为）。
     */
    suspend fun removeTrackEverywhere(context: Context, id: String): Boolean {
        val ok = mutex.withLock {
            withContext(Dispatchers.IO) {
                var allOk = true
                val localFile = File(context.filesDir, LOCAL_FILE)
                val local = readTracks(localFile)
                if (local.any { it.id == id } &&
                    !writeTracks(localFile, local.filterNot { it.id == id })
                ) {
                    allOk = false
                }
                val favFile = File(context.filesDir, FAV_FILE)
                val fav = readTracks(favFile)
                if (fav.any { it.id == id } &&
                    !writeTracks(favFile, fav.filterNot { it.id == id })
                ) {
                    allOk = false
                }
                val playlistFile = File(context.filesDir, PLAYLIST_FILE)
                val pls = readPlaylists(playlistFile)
                if (pls.any { pl -> pl.tracks.any { it.id == id } }) {
                    val updated = pls.map { pl ->
                        if (pl.tracks.any { it.id == id }) {
                            pl.copy(tracks = pl.tracks.filterNot { it.id == id })
                        } else {
                            pl
                        }
                    }
                    if (!writePlaylists(playlistFile, updated)) allOk = false
                }
                allOk
            }
        }
        MusicHistoryStore.removePlay(context, id)
        return ok
    }

    // ---- 收藏 ----

    suspend fun favList(context: Context): List<Track> = mutex.withLock {
        withContext(Dispatchers.IO) {
            readTracks(File(context.filesDir, FAV_FILE))
        }
    }

    suspend fun isFav(context: Context, id: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            readTracks(File(context.filesDir, FAV_FILE)).any { it.id == id }
        }
    }

    /** 切换收藏，返回切换后是否为收藏态。 */
    suspend fun toggleFav(context: Context, track: Track): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = File(context.filesDir, FAV_FILE)
            val cur = readTracks(f).toMutableList()
            val exists = cur.any { it.id == track.id }
            if (exists) cur.removeAll { it.id == track.id } else cur.add(0, track)
            // 写盘失败返回写盘前状态：磁盘没变，UI 收藏态也不应翻转
            if (writeTracks(f, cur)) !exists else exists
        }
    }

    // ---- 自建歌单（v0.84.0） ----

    private const val PLAYLIST_FILE = "music_playlists.json"

    private fun readPlaylists(f: File): List<MusicPlaylist> = runCatching {
        if (!f.isFile) return emptyList()
        json.decodeFromString(ListSerializer(MusicPlaylist.serializer()), f.readText())
    }.onFailure {
        Log.w(TAG, "歌单读取失败", it)
        // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
        AtomicFile.preserveCorrupt(f)
        corruptLoaded = true
    }.getOrDefault(emptyList())

    private fun writePlaylists(f: File, list: List<MusicPlaylist>): Boolean {
        if (corruptLoaded) {
            LogBus.w(TAG, "音乐数据读坏未处理，拒绝写盘覆盖：${f.name}")
            return false
        }
        val ok = runCatching {
            // v0.170.0：原子写盘
            AtomicFile.writeText(f, json.encodeToString(ListSerializer(MusicPlaylist.serializer()), list))
        }.onFailure { Log.w(TAG, "歌单写入失败", it) }.getOrDefault(false)
        if (!ok) LogBus.w(TAG, "歌单写入失败")
        return ok
    }

    suspend fun playlistList(context: Context): List<MusicPlaylist> = mutex.withLock {
        withContext(Dispatchers.IO) {
            readPlaylists(File(context.filesDir, PLAYLIST_FILE))
                .sortedByDescending { it.createdAt }
        }
    }

    suspend fun getPlaylist(context: Context, id: String): MusicPlaylist? = mutex.withLock {
        withContext(Dispatchers.IO) {
            readPlaylists(File(context.filesDir, PLAYLIST_FILE)).firstOrNull { it.id == id }
        }
    }

    suspend fun createPlaylist(context: Context, name: String): MusicPlaylist? =
        mutex.withLock {
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
                // 写盘失败返回空：调用方已按空判失败并提示，不谎报已创建
                if (writePlaylists(f, cur)) pl else null
            }
        }

    suspend fun renamePlaylist(context: Context, id: String, name: String): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val nm = name.trim()
                if (nm.isEmpty()) return@withContext false
                val f = File(context.filesDir, PLAYLIST_FILE)
                val cur = readPlaylists(f)
                if (cur.none { it.id == id } || cur.any { it.name == nm && it.id != id }) {
                    return@withContext false
                }
                writePlaylists(f, cur.map { if (it.id == id) it.copy(name = nm) else it })
            }
        }

    /** v0.188.0：写盘成败如实返回，供歌单页失败时提示保存失败而非静默。 */
    suspend fun deletePlaylist(context: Context, id: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = File(context.filesDir, PLAYLIST_FILE)
            writePlaylists(f, readPlaylists(f).filterNot { it.id == id })
        }
    }

    /** 往歌单加歌；已在则返回 false。 */
    suspend fun addToPlaylist(context: Context, playlistId: String, track: Track): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val f = File(context.filesDir, PLAYLIST_FILE)
                val cur = readPlaylists(f)
                val pl = cur.firstOrNull { it.id == playlistId } ?: return@withContext false
                if (pl.tracks.any { it.id == track.id }) return@withContext false
                writePlaylists(
                    f,
                    cur.map { if (it.id == playlistId) it.copy(tracks = it.tracks + track) else it },
                )
            }
        }

    /** v0.188.0：写盘成败如实返回（歌单不存在时仍写回原表，返回值即写盘结果）。 */
    suspend fun removeFromPlaylist(context: Context, playlistId: String, trackId: String): Boolean =
        mutex.withLock {
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

    /**
     * v0.89.0：手动排序——把 from 位置的歌移到 to（邻位交换用 removeAt/add 一次到位）。
     * 越界直接 return；其余照抄 readPlaylists/writePlaylists 模式。
     */
    /**
     * v0.188.0：写盘成败如实返回。无操作分支（歌单不存在、越界、原地）不写盘，
     * 返回 true，不让调用方把“没动”误报成保存失败。
     */
    suspend fun moveTrack(context: Context, playlistId: String, from: Int, to: Int): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val f = File(context.filesDir, PLAYLIST_FILE)
                val cur = readPlaylists(f)
                val pl = cur.firstOrNull { it.id == playlistId } ?: return@withContext false
                val tracks = pl.tracks
                if (from !in tracks.indices || to !in tracks.indices || from == to) return@withContext true
                val mutable = tracks.toMutableList()
                mutable.add(to, mutable.removeAt(from))
                writePlaylists(
                    f,
                    cur.map { if (it.id == playlistId) pl.copy(tracks = mutable) else it },
                )
            }
        }

    /** v0.89.0：批量删除（多选模式）。v0.188.0：写盘成败如实返回，空选集不写盘返回 true。 */
    suspend fun removeTracks(context: Context, playlistId: String, ids: Set<String>): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (ids.isEmpty()) return@withContext true
                val f = File(context.filesDir, PLAYLIST_FILE)
                val cur = readPlaylists(f)
                writePlaylists(
                    f,
                    cur.map {
                        if (it.id == playlistId) it.copy(tracks = it.tracks.filterNot { t -> t.id in ids })
                        else it
                    },
                )
            }
        }

    /** v0.89.0：按歌名排序（name.lowercase()）写回。v0.188.0：写盘成败如实返回。 */
    suspend fun sortByName(context: Context, playlistId: String): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val f = File(context.filesDir, PLAYLIST_FILE)
                val cur = readPlaylists(f)
                writePlaylists(
                    f,
                    cur.map {
                        if (it.id == playlistId) {
                            it.copy(tracks = it.tracks.sortedBy { t -> t.name.lowercase() })
                        } else it
                    },
                )
            }
        }
}
