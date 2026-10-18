package com.movieshell.data.source

import android.content.Context

/**
 * v0.93：嗅探可配参数。
 *
 * - deepSniffConcurrency：深度嗅探并发数（4/6/8）
 * - probeRetry：可疑 URL 探测失败重试次数（0/1/2）
 * - probeTimeoutMs：单次探测超时毫秒（默认 5 秒，暂不开放 UI 调整）
 *
 * SharedPreferences（sniff_config）持久化；改完即时生效——并发数在每次
 * 深度嗅探启动时读取，重试/超时在每次 probeContentType 调用时读取。
 */
object SniffConfig {

    private const val PREF = "sniff_config"
    private const val K_CONCURRENCY = "deep_sniff_concurrency"
    private const val K_RETRY = "probe_retry"
    private const val K_TIMEOUT = "probe_timeout_ms"

    /** 深度嗅探并发数 */
    var deepSniffConcurrency: Int = 6
        private set

    /** 可疑 URL 探测失败重试次数 */
    var probeRetry: Int = 1
        private set

    /** 单次探测超时毫秒 */
    var probeTimeoutMs: Long = 5_000L
        private set

    @Volatile
    private var loaded = false

    /** 幂等加载：首次调用时同步读 prefs；未加载时各字段保持默认值 */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            deepSniffConcurrency = p.getInt(K_CONCURRENCY, 6).coerceIn(2, 16)
            probeRetry = p.getInt(K_RETRY, 1).coerceIn(0, 5)
            probeTimeoutMs = p.getLong(K_TIMEOUT, 5_000L).coerceIn(1_000L, 30_000L)
            loaded = true
        }
    }

    /** 更新并持久化（未传的字段保持原值） */
    fun update(context: Context, concurrency: Int = deepSniffConcurrency, retry: Int = probeRetry) {
        deepSniffConcurrency = concurrency.coerceIn(2, 16)
        probeRetry = retry.coerceIn(0, 5)
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt(K_CONCURRENCY, deepSniffConcurrency)
            .putInt(K_RETRY, probeRetry)
            .putLong(K_TIMEOUT, probeTimeoutMs)
            .apply()
    }
}
