package com.movieshell.data.repo

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Subject
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首页电影布局（v0.78.3，对标 DsPlayer 的布局切换）。
 *
 * - GRID_3：海报网格（3 列，默认，最多展示）
 * - GRID_2：大海报（2 列）
 * - COMPACT_LIST：紧凑列表（小图 + 文字）
 * - TEXT_ONLY：无图文字（纯文字行，密度最高）
 */
enum class HomeLayout(
    val label: String,
    val icon: ImageVector,
    /** 网格列数；列表模式按 1 列渲染行 */
    val columns: Int,
) {
    GRID_3("海报网格", Icons.Filled.GridView, 3),
    GRID_2("大海报", Icons.Filled.Image, 2),
    COMPACT_LIST("紧凑列表", Icons.Filled.List, 1),
    TEXT_ONLY("无图文字", Icons.Filled.Subject, 1),
}

object HomeSettings {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_HOME_LAYOUT = "home_layout"
    private const val KEY_SOURCE_PICKER_LAYOUT = "source_picker_layout"

    private val _layout = MutableStateFlow(HomeLayout.GRID_3)
    val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

    // v0.188.0：换源器 6 种布局（0 大卡 1 图标 2 列表 3 宽卡 4 中卡 5 紧凑），
    // 与首页布局同机制持久化，关弹窗重开不再回默认大卡。
    private val _pickerLayout = MutableStateFlow(0)
    val pickerLayout: StateFlow<Int> = _pickerLayout.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOME_LAYOUT, null)
        _layout.value = runCatching { HomeLayout.valueOf(name!!) }
            .getOrDefault(HomeLayout.GRID_3)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _pickerLayout.value = prefs.getInt(KEY_SOURCE_PICKER_LAYOUT, 0).coerceIn(0, 5)
    }

    fun setPickerLayout(context: Context, mode: Int) {
        val clamped = mode.coerceIn(0, 5)
        _pickerLayout.value = clamped
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_SOURCE_PICKER_LAYOUT, clamped)
                .apply()
        }.onFailure { LogBus.w("HomeSettings", "换源器布局持久化失败：${it.message}") }
    }

    fun setLayout(context: Context, layout: HomeLayout) {
        _layout.value = layout
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HOME_LAYOUT, layout.name)
                .apply()
        }.onFailure { LogBus.w("HomeSettings", "首页布局持久化失败：${it.message}") }
        LogBus.i("HomeSettings", "首页布局切换：${layout.label}")
    }
}
