package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.MusicApi.Track
import kotlinx.coroutines.Dispatchers
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

    private fun readStrings(f: File): List<String> = runCatching {
        if (!f.isFile) return emptyList()
        // 读侧去重保序：旧文件/并发写留下的重复词会让 UI 的 raw-string lazy key 撞车
        json.decodeFromString(ListSerializer(String.serializer()), f.readText()).distinct()
    }.onFailure { Log.w(TAG, "读取失败：${f.name}", it) }.getOrDefault(emptyList())

    private fun readTracks(f: File): List<Track> = runCatching {
        if (!f.isFile) return emptyList()
        // 读侧按 id 去重保序：同曲重复记录会让 UI 的 lazy key 撞车
        json.decodeFromString(ListSerializer(Track.serializer()), f.readText()).distinctBy { it.id }
    }.onFailure { Log.w(TAG, "读取失败：${f.name}", it) }.getOrDefault(emptyList())

    // ---- 搜索历史 ----

    suspend fun searchHistory(context: Context): List<String> = withContext(Dispatchers.IO) {
        readStrings(File(context.filesDir, SEARCH_FILE))
    }

    suspend fun addSearch(context: Context, query: String) = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext
        val f = File(context.filesDir, SEARCH_FILE)
        val cur = (listOf(q) + readStrings(f).filterNot { it == q }).take(MAX_SEARCH)
        runCatching { f.writeText(json.encodeToString(ListSerializer(String.serializer()), cur)) }
            .onFailure { Log.w(TAG, "搜索历史写入失败", it) }
    }

    suspend fun removeSearch(context: Context, query: String) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, SEARCH_FILE)
        val cur = readStrings(f).filterNot { it == query }
        runCatching { f.writeText(json.encodeToString(ListSerializer(String.serializer()), cur)) }
            .onFailure { Log.w(TAG, "搜索历史写入失败", it) }
    }

    suspend fun clearSearch(context: Context) = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, SEARCH_FILE).delete() }
            .onFailure { Log.w(TAG, "搜索历史清空失败", it) }
    }

    // ---- 播放历史 ----

    suspend fun playHistory(context: Context): List<Track> = withContext(Dispatchers.IO) {
        readTracks(File(context.filesDir, PLAY_FILE))
    }

    /** 播歌时调：去重后放最前。 */
    suspend fun recordPlay(context: Context, track: Track) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, PLAY_FILE)
        val cur = (listOf(track) + readTracks(f).filterNot { it.id == track.id }).take(MAX_PLAY)
        runCatching { f.writeText(json.encodeToString(ListSerializer(Track.serializer()), cur)) }
            .onFailure { Log.w(TAG, "播放历史写入失败", it) }
    }

    suspend fun removePlay(context: Context, id: String) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, PLAY_FILE)
        val cur = readTracks(f).filterNot { it.id == id }
        runCatching { f.writeText(json.encodeToString(ListSerializer(Track.serializer()), cur)) }
            .onFailure { Log.w(TAG, "播放历史写入失败", it) }
    }

    suspend fun clearPlay(context: Context) = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, PLAY_FILE).delete() }
            .onFailure { Log.w(TAG, "播放历史清空失败", it) }
    }
}
