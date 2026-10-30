package com.movieshell.data.source

import android.content.Context

/**
 * v0.93：嗅探可配参数。
 *
 * - deepSniffConcurrency：深度嗅探并发数（4/6/8）
 * - probeRetry：可疑 URL 探测失败重试次数（0/1/2）
 * - probeTimeoutMs：单次探测超时毫秒（默认 5 秒，暂不开放 UI 调整）
 * - adFilterEnabled：广告过滤总开关（默认开；管候选排除 + 去广告 JS 注入 + 广告域名拦截
 *   + m3u8 分片过滤）
 *
 * SharedPreferences（sniff_config）持久化；改完即时生效——并发数在每次
 * 深度嗅探启动时读取，重试/超时在每次 probeContentType 调用时读取，
 * 广告开关在每次 addCandidate/拦截/注入时读取。
 */
object SniffConfig {

    private const val PREF = "sniff_config"
    private const val K_CONCURRENCY = "deep_sniff_concurrency"
    private const val K_RETRY = "probe_retry"
    private const val K_TIMEOUT = "probe_timeout_ms"
    private const val K_AD_FILTER = "ad_filter_enabled"
    private const val K_SNIFF_TIMEOUT = "sniff_timeout_ms"
    private const val K_AUTO_SNIFF_TIMEOUT = "auto_sniff_timeout_ms"
    private const val K_PARSE_BUDGET = "parse_budget_ms"
    private const val K_UA_MODE = "sniff_ua_mode"

    /** 嗅探页 UA：0=手机（默认，去掉 WebView 的 wv 标记）1=电脑 */
    const val UA_MOBILE = 0
    const val UA_DESKTOP = 1
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** 深度嗅探并发数 */
    var deepSniffConcurrency: Int = 6
        private set

    /** 可疑 URL 探测失败重试次数 */
    var probeRetry: Int = 1
        private set

    /** 单次探测超时毫秒 */
    var probeTimeoutMs: Long = 5_000L
        private set

    /** 广告过滤总开关（v0.94） */
    var adFilterEnabled: Boolean = true
        private set

    /** v0.294.7（M8）：手动嗅探页超时（到点提示，不关页） */
    var sniffTimeoutMs: Long = 20_000L
        private set

    /** v0.294.7：自动嗅探（type=0 解析线 / 规则页面嗅探）单条超时 */
    var autoSniffTimeoutMs: Long = 15_000L
        private set

    /** v0.294.7（H3）：一次解析的总时间预算（全部线路并发合计） */
    var parseBudgetMs: Long = 45_000L
        private set

    /** v0.294.7（M8）：嗅探 UA 模式 */
    var uaMode: Int = UA_MOBILE
        private set

    fun uaString(): String = if (uaMode == UA_DESKTOP) DESKTOP_UA else MOBILE_UA

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
            adFilterEnabled = p.getBoolean(K_AD_FILTER, true)
            sniffTimeoutMs = p.getLong(K_SNIFF_TIMEOUT, 20_000L).coerceIn(5_000L, 120_000L)
            autoSniffTimeoutMs = p.getLong(K_AUTO_SNIFF_TIMEOUT, 15_000L).coerceIn(5_000L, 60_000L)
            parseBudgetMs = p.getLong(K_PARSE_BUDGET, 45_000L).coerceIn(10_000L, 180_000L)
            uaMode = p.getInt(K_UA_MODE, UA_MOBILE).coerceIn(0, 1)
            loaded = true
        }
    }

    /** 更新并持久化（未传的字段保持原值） */
    fun update(
        context: Context,
        concurrency: Int = deepSniffConcurrency,
        retry: Int = probeRetry,
        adFilter: Boolean = adFilterEnabled,
        probeTimeout: Long = probeTimeoutMs,
        sniffTimeout: Long = sniffTimeoutMs,
        autoSniffTimeout: Long = autoSniffTimeoutMs,
        parseBudget: Long = parseBudgetMs,
        ua: Int = uaMode,
    ) {
        deepSniffConcurrency = concurrency.coerceIn(2, 16)
        probeRetry = retry.coerceIn(0, 5)
        adFilterEnabled = adFilter
        probeTimeoutMs = probeTimeout.coerceIn(1_000L, 30_000L)
        sniffTimeoutMs = sniffTimeout.coerceIn(5_000L, 120_000L)
        autoSniffTimeoutMs = autoSniffTimeout.coerceIn(5_000L, 60_000L)
        parseBudgetMs = parseBudget.coerceIn(10_000L, 180_000L)
        uaMode = ua.coerceIn(0, 1)
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt(K_CONCURRENCY, deepSniffConcurrency)
            .putInt(K_RETRY, probeRetry)
            .putLong(K_TIMEOUT, probeTimeoutMs)
            .putBoolean(K_AD_FILTER, adFilterEnabled)
            .putLong(K_SNIFF_TIMEOUT, sniffTimeoutMs)
            .putLong(K_AUTO_SNIFF_TIMEOUT, autoSniffTimeoutMs)
            .putLong(K_PARSE_BUDGET, parseBudgetMs)
            .putInt(K_UA_MODE, uaMode)
            .apply()
    }
}
