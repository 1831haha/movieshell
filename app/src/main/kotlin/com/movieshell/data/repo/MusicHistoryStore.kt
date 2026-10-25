package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.MusicApi.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.84.0：音乐搜索历史 + 播放历史。
 *
 * - 搜索历史：在线 tab 搜过的关键词，最近 15 个。
 * - 播放历史：播过的歌（去重、最近的排最前），最近 100 首。
 * 都落在 filesDir，跟着 MusicStore 的风格：suspend + Dispatchers.IO，异常吞掉打日志。
 */
object MusicHistoryStore {
    private const val TAG = "MusicHistoryStore"
    private const val SEARCH_FILE = "music_search_history.json"
    private const val PLAY_FILE = "music_play_history.json"
    private const val MAX_SEARCH = 15
    private const val MAX_PLAY = 100

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * v0.186.0：搜索历史与播放历史两文件共用同一把锁；清空删文件也在锁内，不与写盘竞态。
     * v0.216.0（C4）：读坏标记与 MusicStore 同款——解析失败即置 true，此后写盘拒绝，
     * 直到用户清掉损坏文件，防止空表覆盖旧历史。
     */
    private val mutex = Mutex()
    private var corruptLoaded = false

    /** 任一历史文件的损坏备份是否还在，供 UI 横幅判断。 */
    suspend fun hasCorruptBackup(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            listOf(SEARCH_FILE, PLAY_FILE).any {
                File(context.filesDir, "$it.corrupt.bak").isFile
            }
        }
    }

    /** 用户点的“重新开始”：删掉损坏的历史文件与备份，标记清零。 */
    suspend fun clearCorrupt(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                listOf(SEARCH_FILE, PLAY_FILE).forEach {
                    File(context.filesDir, it).delete()
                    File(context.filesDir, "$it.corrupt.bak").delete()
                }
                corruptLoaded = false
                true
            }.getOrDefault(false)
        }
    }

    private fun readStrings(f: File): List<String> = runCatching {
        if (!f.isFile) return emptyList()
        // 读侧去重保序：旧文件/并发写留下的重复词会让 UI 的 raw-string lazy key 撞车
        json.decodeFromString(ListSerializer(String.serializer()), f.readText()).distinct()
    }.onFailure {
        Log.w(TAG, "读取失败：${f.name}", it)
        // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
        AtomicFile.preserveCorrupt(f)
        corruptLoaded = true
    }.getOrDefault(emptyList())

    private fun readTracks(f: File): List<Track> = runCatching {
        if (!f.isFile) return emptyList()
        // 读侧按 id 去重保序：同曲重复记录会让 UI 的 lazy key 撞车
        json.decodeFromString(ListSerializer(Track.serializer()), f.readText()).distinctBy { it.id }
    }.onFailure {
        Log.w(TAG, "读取失败：${f.name}", it)
        // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
        AtomicFile.preserveCorrupt(f)
        corruptLoaded = true
    }.getOrDefault(emptyList())

    /** v0.186.0：写盘成败如实返回，失败记 LogBus 日志，不再静默吞掉。 */
    private fun writeStrings(f: File, list: List<String>): Boolean {
        if (corruptLoaded) {
            Log.w(TAG, "音乐历史读坏未处理，拒绝写盘覆盖：${f.name}")
            return false
        }
        val ok = runCatching {
            // v0.170.0：原子写盘
            AtomicFile.writeText(f, json.encodeToString(ListSerializer(String.serializer()), list))
        }.onFailure { Log.w(TAG, "搜索历史写入失败", it) }.getOrDefault(false)
        if (!ok) LogBus.w(TAG, "搜索历史写入失败：${f.name}")
        return ok
    }

    private fun writeTracks(f: File, list: List<Track>): Boolean {
        if (corruptLoaded) {
            Log.w(TAG, "音乐历史读坏未处理，拒绝写盘覆盖：${f.name}")
            return false
        }
        val ok = runCatching {
            // v0.170.0：原子写盘
            AtomicFile.writeText(f, json.encodeToString(ListSerializer(Track.serializer()), list))
        }.onFailure { Log.w(TAG, "播放历史写入失败", it) }.getOrDefault(false)
        if (!ok) LogBus.w(TAG, "播放历史写入失败：${f.name}")
        return ok
    }

    // ---- 搜索历史 ----

    suspend fun searchHistory(context: Context): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            readStrings(File(context.filesDir, SEARCH_FILE))
        }
    }

    suspend fun addSearch(context: Context, query: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext
            val f = File(context.filesDir, SEARCH_FILE)
            val cur = (listOf(q) + readStrings(f).filterNot { it == q }).take(MAX_SEARCH)
            writeStrings(f, cur)
        }
    }

    suspend fun removeSearch(context: Context, query: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = File(context.filesDir, SEARCH_FILE)
            val cur = readStrings(f).filterNot { it == query }
            writeStrings(f, cur)
        }
    }

    suspend fun clearSearch(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { File(context.filesDir, SEARCH_FILE).delete() }
                .onFailure { Log.w(TAG, "搜索历史清空失败", it) }
            // 显式清空即用户主动放弃旧历史：连损坏备份一起清，标记复位
            runCatching { File(context.filesDir, "$SEARCH_FILE.corrupt.bak").delete() }
            corruptLoaded = false
        }
    }

    // ---- 播放历史 ----

    /** 播歌时调：去重后放最前。 */
    suspend fun recordPlay(context: Context, track: Track) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = File(context.filesDir, PLAY_FILE)
            val cur = (listOf(track) + readTracks(f).filterNot { it.id == track.id }).take(MAX_PLAY)
            writeTracks(f, cur)
        }
    }

    /**
     * v0.188.0 M23：仅保留删歌联动用——MusicStore.removeTrackEverywhere 调这里
     * 清掉播放历史里的同一首。播放历史读/清空不在本任务范围，不恢复。
     */
    suspend fun removePlay(context: Context, id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = File(context.filesDir, PLAY_FILE)
            val cur = readTracks(f).filterNot { it.id == id }
            writeTracks(f, cur)
        }
    }
}
