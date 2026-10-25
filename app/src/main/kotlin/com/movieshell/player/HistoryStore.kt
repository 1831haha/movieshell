package com.movieshell.player

import android.content.Context
import android.util.Log
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
    /** v0.24.0：vod=点播，live=直播（老数据缺字段时默认 vod） */
    val kind: String = "vod",
    /** v0.75.0：剧 ID（老数据缺字段时默认 ""，不可用于继续观看） */
    val vodId: String = "",
    /** v0.75.0：集数下标（老数据缺字段时默认 -1） */
    val episodeIndex: Int = -1,
    /** v0.189.0：继续观看跳源用（sub/drpy/py/php，老数据缺字段时默认 ""，回退当前源） */
    val sourceType: String = "",
    /** v0.189.0：继续观看跳源用（订阅站站点 key 或本地规则名，老数据缺字段时默认 ""） */
    val sourceKey: String = "",
)

/**
 * 播放历史记录，SharedPreferences 持久化。
 *
 * 按 url 去重、最新记录排在最前面，最多保留 [MAX_SIZE] 条。
 */
object HistoryStore {

    private const val TAG = "HistoryStore"
    private const val PREFS_NAME = "player_history"
    private const val KEY_LIST = "history_list"
    /** v0.170.0：解析失败的原串保全键——读坏的旧数据先留证据，下次写盘不再悄悄清空 */
    private const val KEY_LIST_CORRUPT_BAK = "history_list_corrupt_bak"
    private const val MAX_SIZE = 50

    // v0.170.0：ignoreUnknownKeys——HistoryEntry 加字段后，旧文件/新文件互读不再抛错走清空链；
    // prefs 的 apply 本身是平台级原子写（先写备份文件再改名），文件级 helper 不适用这里。
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * v0.216.0（C5）：读坏标记——解析失败后置 true，此后 saveProgress/delete
     * 一律拒绝写盘，直到用户在历史页点“重新开始”清掉损坏数据。防止
     * “随便播一个视频就把旧的全部历史顶掉”。HistoryStore 无锁，标记用 Volatile。
     */
    @Volatile
    private var corruptLoaded = false

    /** 损坏备份是否还在，供历史页横幅判断。 */
    fun hasCorruptBackup(context: Context): Boolean =
        prefs(context).contains(KEY_LIST_CORRUPT_BAK)

    /**
     * 用户在横幅上点的“重新开始”：删掉损坏的历史与备份，标记清零
     * （备份本身就是坏串，无法解析回表，变显式放弃而非静默丢失）。
     */
    fun clearCorrupt(context: Context) {
        prefs(context).edit().remove(KEY_LIST).remove(KEY_LIST_CORRUPT_BAK).apply()
        corruptLoaded = false
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存（或更新）一条播放进度 */
    fun saveProgress(
        context: Context,
        title: String,
        url: String,
        positionMs: Long,
        durationMs: Long = 0L,
        kind: String = "vod",
        vodId: String = "",
        episodeIndex: Int = -1,
        sourceType: String = "",
        sourceKey: String = "",
    ) {
        if (url.isBlank()) return
        val list = getHistory(context).toMutableList()
        if (corruptLoaded) {
            // 注意：守卫必须在 getHistory 之后——第一次读坏时标记才刚立起来，
            // 先判后读会漏过第一次覆盖。
            Log.w(TAG, "历史记录读坏未处理，拒绝写盘覆盖（请先在历史页处理损坏数据）")
            return
        }
        list.removeAll { it.url == url }
        list.add(
            0,
            HistoryEntry(
                title = title,
                url = url,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                timestamp = System.currentTimeMillis(),
                kind = kind,
                vodId = vodId,
                episodeIndex = episodeIndex,
                sourceType = sourceType,
                sourceKey = sourceKey,
            ),
        )
        while (list.size > MAX_SIZE) list.removeLast()
        prefs(context).edit()
            .putString(KEY_LIST, json.encodeToString(list))
            .apply()
    }

    /** 读取全部历史记录（按最近播放排序） */
    fun getHistory(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<HistoryEntry>>(raw) }
            .onFailure {
                Log.w(TAG, "历史记录解析失败", it)
                // v0.170.0：读坏先把原串保全到备份键，下次写盘不再悄悄清空
                runCatching {
                    prefs(context).edit().putString(KEY_LIST_CORRUPT_BAK, raw).apply()
                }
                corruptLoaded = true
            }
            .getOrDefault(emptyList())
    }

    /** 取某 url 的上次进度，不存在返回 0 */
    fun getProgress(context: Context, url: String): Long =
        getHistory(context).firstOrNull { it.url == url }?.positionMs ?: 0L

    /**
     * v0.217.0（F61）：删单条记录——按 URL＋时间戳定位（列表 key 本来
     * 就是 url@timestamp）。原来按 URL 删，同一地址的多条记录会被一次
     * 删光；按 tab 清空点播时，同地址的直播记录也会被顺带删掉。
     */
    fun deleteEntry(context: Context, url: String, timestamp: Long) {
        val list = getHistory(context).filterNot { it.url == url && it.timestamp == timestamp }
        if (corruptLoaded) {
            Log.w(TAG, "历史记录读坏未处理，拒绝写盘覆盖")
            return
        }
        prefs(context).edit()
            .putString(KEY_LIST, json.encodeToString(list))
            .apply()
    }

    /** 清空全部历史（显式清空即用户主动放弃，连损坏备份一起清，标记复位） */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LIST).remove(KEY_LIST_CORRUPT_BAK).apply()
        corruptLoaded = false
    }
}
