package com.movieshell.player

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一条播放历史记录 */
@Serializable
data class HistoryEntry(
    /** 影片/剧集标题 */
    val title: String,
    /** 播放地址（去重键） */
    val url: String,
    /** 上次播放进度（毫秒） */
    val positionMs: Long,
    /** 媒体总时长（毫秒，未知时为 0） */
    val durationMs: Long = 0L,
    /** 记录时间（毫秒时间戳） */
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 播放历史记录，SharedPreferences 持久化。
 *
 * 按 url 去重、最新记录排在最前面，最多保留 [MAX_SIZE] 条。
 */
object HistoryStore {

    private const val PREFS_NAME = "player_history"
    private const val KEY_LIST = "history_list"
    private const val MAX_SIZE = 50

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存（或更新）一条播放进度 */
    fun saveProgress(
        context: Context,
        title: String,
        url: String,
        positionMs: Long,
        durationMs: Long = 0L,
    ) {
        if (url.isBlank()) return
        val list = getHistory(context).toMutableList()
        list.removeAll { it.url == url }
        list.add(
            0,
            HistoryEntry(
                title = title,
                url = url,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                timestamp = System.currentTimeMillis(),
            ),
        )
        while (list.size > MAX_SIZE) list.removeLast()
        prefs(context).edit()
            .putString(KEY_LIST, Json.encodeToString(list))
            .apply()
    }

    /** 读取全部历史记录（按最近播放排序） */
    fun getHistory(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { Json.decodeFromString<List<HistoryEntry>>(raw) }
            .getOrDefault(emptyList())
    }

    /** 取某 url 的上次进度，不存在返回 0 */
    fun getProgress(context: Context, url: String): Long =
        getHistory(context).firstOrNull { it.url == url }?.positionMs ?: 0L

    /** 清空全部历史 */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LIST).apply()
    }
}
