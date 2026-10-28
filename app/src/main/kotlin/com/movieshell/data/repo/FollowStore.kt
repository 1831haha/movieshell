package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** v0.87.0：已完结——连续多日集数不变自动标记，不再参与更新检测 */
    val finished: Boolean = false,
    /** v0.87.0：上次集数变化时间（0=老数据，按当前时间起算） */
    val lastEpisodeChangeAt: Long = 0L,
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
    /** v0.170.0：解析失败的原串保全键——读坏的旧数据先留证据，下次写盘不再悄悄清空 */
    private const val KEY_LIST_CORRUPT_BAK = "follow_list_corrupt_bak"

    private val json = Json { ignoreUnknownKeys = true }

    private val _list = MutableStateFlow<List<FollowItem>>(emptyList())
    val list: StateFlow<List<FollowItem>> = _list.asStateFlow()

    private var inited = false

    /**
     * v0.186.0：读改写同一把锁——follow/unfollow/mark 系列的读表加写盘全程串行，
     * UI 与 FollowCheckWorker 并发写同一 prefs 时后写者不再覆盖先写者的改动。
     * 纯读（readSync/isFollowing/getWatchedEpisode）不动：SharedPreferences 单值读是原子的，
     * commit 写盘同步可见；init 的 Synchronized 守门与开关单值读写也不重复加锁。
     */
    private val mutex = Mutex()

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        _list.value = readAll(context)
    }

    fun idOf(sourceKey: String, vodId: String) = "$sourceKey|$vodId"

    suspend fun isFollowing(context: Context, id: String): Boolean =
        withContext(Dispatchers.IO) { readAll(context).any { it.id == id } }

    /** v0.71.0：读"看到第几集"（-1=没看过），供详情页选集已看标记。 */
    suspend fun getWatchedEpisode(context: Context, id: String): Int =
        withContext(Dispatchers.IO) {
            readAll(context).find { it.id == id }?.watchedEpisode ?: -1
        }

    /** 幂等追剧：已存在则刷新集数。 */
    suspend fun follow(context: Context, item: FollowItem): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    // v0.87.0：新追时集数变化计时从当前起算
                    // v0.259.0：空封面合并——播放器顶栏追剧时没有封面图，
                    // 新条目封面为空时不得冲掉库内已有封面；已看集数同理不回退。
                    val existing = readAll(context).find { it.id == item.id }
                    val fresh = item.copy(
                        updatedAt = System.currentTimeMillis(),
                        lastEpisodeChangeAt = System.currentTimeMillis(),
                        pic = item.pic.ifBlank { existing?.pic.orEmpty() },
                        watchedEpisode = maxOf(item.watchedEpisode, existing?.watchedEpisode ?: -1),
                    )
                    val rest = readAll(context).filterNot { it.id == fresh.id }
                    // v0.170.0：写盘失败如实返回 false，不谎报成功
                    if (!writeAll(context, (listOf(fresh) + rest).sortedByDescending { it.updatedAt })) {
                        Log.w(TAG, "追剧写入失败")
                        return@runCatching false
                    }
                    refreshFlow(context)
                    true
                }.onFailure { Log.w(TAG, "追剧写入失败", it) }.getOrDefault(false)
            }
        }

    /** v0.259.0：详情加载回填封面——全屏下首次追剧生成的记录没有封面，
     * 下次进详情页时详情持有封面，若库内该条目封面为空则补写；
     * 已有封面或条目不存在时不写盘，返回 false。 */
    suspend fun backfillPic(context: Context, id: String, pic: String): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (pic.isBlank()) return@runCatching false
                    val all = readAll(context)
                    val target = all.find { it.id == id } ?: return@runCatching false
                    if (target.pic.isNotBlank()) return@runCatching false
                    val ok = writeAll(
                        context,
                        all.map { if (it.id == id) it.copy(pic = pic) else it },
                    )
                    if (ok) refreshFlow(context)
                    ok
                }.onFailure { Log.w(TAG, "追剧封面回填失败", it) }.getOrDefault(false)
            }
        }

    suspend fun unfollow(context: Context, id: String): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    // v0.170.0：写盘失败如实返回 false
                    if (!writeAll(context, readAll(context).filterNot { it.id == id })) {
                        Log.w(TAG, "取消追剧失败")
                        return@runCatching false
                    }
                    refreshFlow(context)
                    true
                }.onFailure { Log.w(TAG, "取消追剧失败", it) }.getOrDefault(false)
            }
        }

    /** 播放时记"看到第几集"。 */
    suspend fun markWatched(context: Context, id: String, episodeIndex: Int) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val ok = writeAll(
                    context,
                    readAll(context).map {
                        if (it.id == id) it.copy(watchedEpisode = maxOf(it.watchedEpisode, episodeIndex))
                        else it
                    },
                )
                if (!ok) Log.w(TAG, "markWatched 写入失败")
                refreshFlow(context)
            }.onFailure { Log.w(TAG, "markWatched 失败", it) }
        }
    }

    /** 更新检测：集数变多则标 NEW 并刷新集数，返回 true 表示有新增。 */
    suspend fun markNew(context: Context, id: String, newCount: Int): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    var added = false
                    val ok = writeAll(
                        context,
                        readAll(context).map {
                            if (it.id == id && newCount > it.episodeCount) {
                                added = true
                                it.copy(
                                    episodeCount = newCount,
                                    hasNew = true,
                                    updatedAt = System.currentTimeMillis(),
                                    // v0.87.0：有更新则完结计时清零
                                    lastEpisodeChangeAt = System.currentTimeMillis(),
                                )
                            } else it
                        },
                    )
                    // v0.170.0：写盘失败不算有新增
                    if (!ok) {
                        Log.w(TAG, "markNew 写入失败")
                        return@runCatching false
                    }
                    if (added) refreshFlow(context)
                    added
                }.onFailure { Log.w(TAG, "markNew 失败", it) }.getOrDefault(false)
            }
        }

    /**
     * v0.188.0 M24：老数据补写集数变化计时起点——只在 lastEpisodeChangeAt 为 0 时写，
     * 已有起点的不动；写盘成功且确实补写时返回 true。Worker 首次以“现在”为起点时
     * 调这里落盘，下次运行不再以“现在”重算，7 天计时才真正走起来。
     */
    suspend fun backfillEpisodeChangeBase(context: Context, id: String, base: Long): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    var changed = false
                    val ok = writeAll(
                        context,
                        readAll(context).map {
                            if (it.id == id && it.lastEpisodeChangeAt <= 0L) {
                                changed = true
                                it.copy(lastEpisodeChangeAt = base)
                            } else {
                                it
                            }
                        },
                    )
                    if (!ok) {
                        Log.w(TAG, "backfillEpisodeChangeBase 写入失败")
                        return@runCatching false
                    }
                    if (changed) refreshFlow(context)
                    changed
                }.onFailure { Log.w(TAG, "backfillEpisodeChangeBase 失败", it) }.getOrDefault(false)
            }
        }

    /** v0.87.0：集数长期不变，标为已完结，不再参与更新检测。 */
    suspend fun markFinished(context: Context, id: String) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val ok = writeAll(
                        context,
                        readAll(context).map { if (it.id == id) it.copy(finished = true) else it },
                    )
                    if (!ok) Log.w(TAG, "markFinished 写入失败")
                    refreshFlow(context)
                }.onFailure { Log.w(TAG, "markFinished 失败", it) }
            }
        }

    /** 看过更新后清 NEW 角标。 */
    suspend fun clearNew(context: Context, id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val ok = writeAll(
                    context,
                    readAll(context).map { if (it.id == id) it.copy(hasNew = false) else it },
                )
                if (!ok) Log.w(TAG, "clearNew 写入失败")
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
            .onFailure {
                Log.w(TAG, "追剧列表解析失败", it)
                // v0.170.0：读坏先把原串保全到备份键，下次写盘不再悄悄清空
                runCatching {
                    prefs(context).edit().putString(KEY_LIST_CORRUPT_BAK, raw).apply()
                }
            }
            .getOrDefault(emptyList())
    }

    /**
     * v0.170.0：commit 同步写盘并返回成败——apply 的失败是静默的，
     * follow()/unfollow() 原先永远返回 true，写盘失败也谎报成功。
     * 全部调用方已在 Dispatchers.IO 上，主线程不走这里。
     */
    private fun writeAll(context: Context, list: List<FollowItem>): Boolean {
        return prefs(context).edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(FollowItem.serializer()), list))
            .commit()
    }

    private fun refreshFlow(context: Context) {
        _list.value = readAll(context)
    }
}
