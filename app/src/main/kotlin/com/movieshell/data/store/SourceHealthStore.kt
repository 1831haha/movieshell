package com.movieshell.data.store

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * v1.1.31：站点健康分——按源记搜索/详情/播放的成功失败，换源（同名剧列表、自动换源）
 * 按分排，稳的源在前。
 *
 * 分数 = 100 * (成功+1) / (成功+失败+2)，没试过的源 50 分起步（拉普拉斯平滑，
 * 避免“只成功 1 次”就冲到 100 分、也避免“只失败 1 次”就沉底）。
 * 记账点：聚合搜索单源结果、详情加载、播放成功（出进度）、播放失败。
 * 成功/失败总数超 200 时对半衰减（保比例、保近期的权重、不无限膨胀）。
 * 播放成功/失败同地址 10 分钟内只记一次（重试链多次回调不重复记账）。
 *
 * 本机 SharedPreferences 存，Application.onCreate 读盘；没 init 时只记内存、不写盘。
 */
@Serializable
data class HealthRecord(
    val success: Int = 0,
    val failure: Int = 0,
)

object SourceHealthStore {
    private const val PREFS = "source_health_store"
    private const val KEY_JSON = "health_json"
    private const val PERSIST_MIN_INTERVAL_MS = 5_000L
    private const val PLAY_THROTTLE_MS = 10 * 60 * 1000L
    private const val DECAY_TOTAL = 200

    private val json = Json { ignoreUnknownKeys = true }

    private var appContext: Context? = null
    private var loaded = false
    private val records = mutableMapOf<String, HealthRecord>()
    private var lastPersistMs = 0L
    private val lastPlayMsByUrl = mutableMapOf<String, Long>()

    @Synchronized
    fun init(context: Context) {
        appContext = context.applicationContext
        ensureLoadedLocked()
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val ctx = appContext ?: return
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null)
            ?: return
        val map = runCatching { json.decodeFromString<Map<String, HealthRecord>>(raw) }
            .getOrDefault(emptyMap())
        records.clear()
        records.putAll(map)
    }

    @Synchronized
    fun score(key: String): Int {
        ensureLoadedLocked()
        val r = records[key] ?: return 50
        return (100 * (r.success + 1) / (r.success + r.failure + 2)).coerceIn(0, 100)
    }

    /**
     * 选源弹窗条目 id → 健康 key：订阅站 id 是 "sub:{url}:{siteKey}"，取最后一段；
     * 本地规则站 id 本身就是 Site key（local:drpy:xxx 等），直接用。
     */
    fun scoreForPickerId(id: String): Int =
        score(if (id.startsWith("sub:")) id.substringAfterLast(":") else id)

    @Synchronized
    fun recordSuccess(key: String) {
        if (key.isBlank()) return
        ensureLoadedLocked()
        val r = records[key] ?: HealthRecord()
        records[key] = decay(r.copy(success = r.success + 1))
        persistLocked()
    }

    @Synchronized
    fun recordFailure(key: String) {
        if (key.isBlank()) return
        ensureLoadedLocked()
        val r = records[key] ?: HealthRecord()
        records[key] = decay(r.copy(failure = r.failure + 1))
        persistLocked()
    }

    /** 播放成功：同地址 10 分钟内只记一次。 */
    @Synchronized
    fun recordPlaySuccessOnce(url: String, key: String) {
        if (url.isBlank() || key.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - (lastPlayMsByUrl[url] ?: 0L) < PLAY_THROTTLE_MS) return
        lastPlayMsByUrl[url] = now
        recordSuccess(key)
    }

    /** 播放失败：同地址 10 分钟内只记一次（重试链多次回调不重复记账）。 */
    @Synchronized
    fun recordPlayFailureOnce(url: String, key: String) {
        if (url.isBlank() || key.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - (lastPlayMsByUrl[url] ?: 0L) < PLAY_THROTTLE_MS) return
        lastPlayMsByUrl[url] = now
        recordFailure(key)
    }

    private fun decay(r: HealthRecord): HealthRecord {
        if (r.success + r.failure <= DECAY_TOTAL) return r
        return HealthRecord(success = r.success / 2, failure = r.failure / 2)
    }

    private fun persistLocked() {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < PERSIST_MIN_INTERVAL_MS) return
        lastPersistMs = now
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, json.encodeToString(MapSerializer(String.serializer(), HealthRecord.serializer()), records))
            .apply()
    }
}
