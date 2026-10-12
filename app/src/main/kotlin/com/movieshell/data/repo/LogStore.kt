package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v0.7.2：运行日志悬浮窗开关持久化（默认关）。
 *
 * - enabled：UI 层 collectAsState() 订阅；MainActivity 根布局按此决定是否渲染小泡；
 * - setEnabled()：写 SharedPreferences 并推送 flow，即时生效。
 */
object LogStore {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_ENABLED = "log_bubble_enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    /** 启动时读回上次保存的开关状态。 */
    fun init(context: Context) {
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        _enabled.value = enabled
        LogBus.i("Log", if (enabled) "运行日志悬浮窗已开启" else "运行日志悬浮窗已关闭")
    }
}
