package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 追剧条目。id = sourceKey|vodId（跨刷新稳定）。
 *
 * sourceType：sub=订阅站（sourceKey=站点 key）/ drpy=本地 drpy 规则（sourceKey=规则文件名）
 *             / py=本地 PY 规则（sourceKey=规则文件名）
 */
@Serializable
data class FollowItem(
    val id: String = "",
    val vodId: String = "",
    val name: String = "",
    val pic: String = "",
    val sourceType: String = "sub",
    val sourceKey: String = "",
    val episodeCount: Int = 0,
    /** 看到第几集（下标从 0 起，-1=没看过） */
    val watchedEpisode: Int = -1,
    /** 有未读更新（列表角标 NEW） */
    val hasNew: Boolean = false,
    val updatedAt: Long = 0L,
)

/**
 * 追剧收藏（v0.26.0，单一入口）。
 *
 * SharedPreferences 存 JSON + StateFlow 对外；更新检测由 FollowCheckWorker 定时跑，
 * 读 list()、调 detail() 比集数、有新增就 markNew() + 发通知。
 */
object FollowStore {

    private const val TAG = "Follow"
    private const val PREFS = "follow_prefs"
    private const val KEY_LIST = "follow_list"

    private val json = Json { ignoreUnknownKeys = true }

    private val _list = MutableStateFlow<List<FollowItem>>(emptyList())
    val list: StateFlow<List<FollowItem>> = _list.asStateFlow()

    private var inited = false

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        _list.value = readAll(context)
    }

    fun idOf(sourceKey: String, vodId: String) = "$sourceKey|$vodId"

    suspend fun isFollowing(context: Context, id: String): Boolean =
        withContext(Dispatchers.IO) { readAll(context).any { it.id == id } }

    /** 幂等追剧：已存在则刷新集数。 */
    suspend fun follow(context: Context, item: FollowItem): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val fresh = item.copy(updatedAt = System.currentTimeMillis())
                val rest = readAll(context).filterNot { it.id == fresh.id }
                writeAll(context, (listOf(fresh) + rest).sortedByDescending { it.updatedAt })
                refreshFlow(context)
                true
            }.onFailure { Log.w(TAG, "追剧写入失败", it) }.getOrDefault(false)
        }

    suspend fun unfollow(context: Context, id: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                writeAll(context, readAll(context).filterNot { it.id == id })
                refreshFlow(context)
                true
            }.onFailure { Log.w(TAG, "取消追剧失败", it) }.getOrDefault(false)
        }

    /** 播放时记"看到第几集"。 */
    suspend fun markWatched(context: Context, id: String, episodeIndex: Int) {
        withContext(Dispatchers.IO) {
            runCatching {
                writeAll(
                    context,
                    readAll(context).map {
                        if (it.id == id) it.copy(watchedEpisode = maxOf(it.watchedEpisode, episodeIndex))
                        else it
                    },
                )
                refreshFlow(context)
            }.onFailure { Log.w(TAG, "markWatched 失败", it) }
        }
    }

    /** 更新检测：集数变多则标 NEW 并刷新集数，返回 true 表示有新增。 */
    suspend fun markNew(context: Context, id: String, newCount: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                var added = false
                writeAll(
                    context,
                    readAll(context).map {
                        if (it.id == id && newCount > it.episodeCount) {
                            added = true
                            it.copy(episodeCount = newCount, hasNew = true, updatedAt = System.currentTimeMillis())
                        } else it
                    },
                )
                if (added) refreshFlow(context)
                added
            }.onFailure { Log.w(TAG, "markNew 失败", it) }.getOrDefault(false)
        }

    /** 看过更新后清 NEW 角标。 */
    suspend fun clearNew(context: Context, id: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                writeAll(context, readAll(context).map { if (it.id == id) it.copy(hasNew = false) else it })
                refreshFlow(context)
            }.onFailure { Log.w(TAG, "clearNew 失败", it) }
        }
    }

    /** 同步读（Worker 用）。 */
    fun readSync(context: Context): List<FollowItem> = readAll(context)

    // ---------- 更新检测开关（设置页） ----------

    private const val KEY_CHECK_ENABLED = "follow_check_enabled"

    fun isCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHECK_ENABLED, true)

    fun setCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECK_ENABLED, enabled).apply()
    }

    // ---------- 内部 ----------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAll(context: Context): List<FollowItem> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(FollowItem.serializer()), raw) }
            .onFailure { Log.w(TAG, "追剧列表解析失败", it) }
            .getOrDefault(emptyList())
    }

    private fun writeAll(context: Context, list: List<FollowItem>) {
        prefs(context).edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(FollowItem.serializer()), list))
            .apply()
    }

    private fun refreshFlow(context: Context) {
        _list.value = readAll(context)
    }
}
