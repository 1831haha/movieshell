package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 主题模式持久化：system（跟随系统）/ dark（深色）/ light（浅色），默认 system。
 *
 * - mode：UI 层 collectAsState() 订阅，切换后全 App 即时生效；
 * - setMode()：写 SharedPreferences 并推送 flow。
 */
object ThemeStore {
    const val MODE_SYSTEM = "system"
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"

    private const val PREFS = "movieshell_prefs"
    private const val KEY_MODE = "theme_mode"

    private val _mode = MutableStateFlow(MODE_SYSTEM)
    val mode: StateFlow<String> = _mode

    /** 启动时读回上次保存的模式；未知值回退 system。 */
    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_SYSTEM)
            .takeIf { it == MODE_DARK || it == MODE_LIGHT || it == MODE_SYSTEM }
            ?: MODE_SYSTEM
        _mode.value = saved
    }

    fun setMode(context: Context, mode: String) {
        val m = when (mode) {
            MODE_DARK, MODE_LIGHT -> mode
            else -> MODE_SYSTEM
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, m)
            .apply()
        _mode.value = m
    }
}
