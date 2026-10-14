package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 主题模式持久化：system（跟随系统）/ dark（深色）/ light（浅色），默认 system。
 *
 * - mode：UI 层 collectAsState() 订阅，切换后全 App 即时生效；
 * - setMode()：写 SharedPreferences 并推送 flow。
 *
 * v0.12.0：加主题色（theme_color，存 ARGB Int），设置页色圈选择，全 App 主色跟随。
 */
object ThemeStore {
    const val MODE_SYSTEM = "system"
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"

    private const val PREFS = "movieshell_prefs"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_COLOR = "theme_color"

    /** 默认主题色：雾蓝（v0.27.0 Soft Tech） */
    const val DEFAULT_COLOR = 0xFF7DD3FC.toInt()

    /** DsPlayer 式候选色 */
    val COLOR_OPTIONS = listOf(
        0xFF9E9E9E.toInt(), // 灰
        0xFFFF6D00.toInt(), // 橙
        0xFFAA00FF.toInt(), // 紫
        0xFF2B7DE9.toInt(), // 蓝（默认）
        0xFF00C853.toInt(), // 绿
        0xFFFF4081.toInt(), // 粉
        0xFFFF3D00.toInt(), // 红
        0xFFFFD600.toInt(), // 黄
        0xFF00BFA5.toInt(), // 青
    )

    private val _mode = MutableStateFlow(MODE_SYSTEM)
    val mode: StateFlow<String> = _mode

    private val _color = MutableStateFlow(DEFAULT_COLOR)
    val color: StateFlow<Int> = _color

    /** 启动时读回上次保存的模式和主题色；未知值回退默认。 */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MODE, MODE_SYSTEM)
            .takeIf { it == MODE_DARK || it == MODE_LIGHT || it == MODE_SYSTEM }
            ?: MODE_SYSTEM
        _mode.value = saved
        _color.value = prefs.getInt(KEY_COLOR, DEFAULT_COLOR)
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

    fun setColor(context: Context, argb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COLOR, argb)
            .apply()
        _color.value = argb
    }
}
