package com.movieshell.data.store

import android.content.Context
import com.movieshell.core.net.Doh
import com.movieshell.core.net.DohConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v1.4.8 A4：DoH 开关与服务地址，独立小 Store（不放 PlayerSettings 里，DoH 与播放设置无关）。
 *
 * - 存在 movieshell_prefs，key 为 doh_enabled / doh_endpoint，默认关；
 * - HttpClient 在 core 层，不反向依赖 data 层：启动时由 XiaYingApp 调 snapshot()
 *   把纯值注给 HttpClient.initDoh()，sharedClient 是 lazy 单例，改完重启 App 才生效。
 */
object DohSettings {

    private const val PREFS = "movieshell_prefs"
    private const val KEY_ENABLED = "doh_enabled"
    private const val KEY_ENDPOINT = "doh_endpoint"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _endpoint = MutableStateFlow(Doh.DEFAULT_ENDPOINT)
    val endpoint: StateFlow<String> = _endpoint

    /** 启动时读回上次保存的开关与地址（地址非法时回默认候选）。 */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _endpoint.value = Doh.resolveEndpoint(prefs.getString(KEY_ENDPOINT, null))
    }

    /** 启动快照：只读纯值，供 HttpClient 建 client 用。 */
    fun snapshot(context: Context): DohConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DohConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            endpoint = Doh.resolveEndpoint(prefs.getString(KEY_ENDPOINT, null)),
        )
    }

    fun setEnabled(context: Context, v: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, v)
            .apply()
        _enabled.value = v
    }

    /**
     * 存服务地址：调用方（网络设置页）先过 Doh.isValidEndpoint 校验格式；
     * 这里只做兜底归一——空白或非法时存默认候选，不存非法值。
     */
    fun setEndpoint(context: Context, raw: String) {
        val endpoint = Doh.resolveEndpoint(raw)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENDPOINT, endpoint)
            .apply()
        _endpoint.value = endpoint
    }
}
