package com.movieshell.ui.home

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Subject
import androidx.compose.ui.graphics.vector.ImageVector
import com.movieshell.core.log.LogBus
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
    // v0.279.0 旧版：四列密排为默认（设计稿 4 列海报墙）；没选过布局的用户拿到它，选过的不变。
    GRID_4("四列密排", Icons.Filled.Apps, 4),
    GRID_3("海报网格", Icons.Filled.GridView, 3),
    GRID_2("大海报", Icons.Filled.Image, 2),
    COMPACT_LIST("紧凑列表", Icons.Filled.List, 1),
    TEXT_ONLY("无图文字", Icons.Filled.Subject, 1),
}

object HomeSettings {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_HOME_LAYOUT = "home_layout"
    private const val KEY_SOURCE_PICKER_LAYOUT = "source_picker_layout"
    private const val KEY_PICKER_MODE = "source_picker_mode"

    // v1.1.25：换源器容器形态——悬浮窗（小居中 Dialog）/ 底部弹窗
    // （ModalBottomSheet），默认悬浮窗；与 pickerLayout 同机制持久化，
    // 关弹窗重开、进程重建都不再回默认。
    enum class PickerMode(val label: String) {
        FLOAT("悬浮窗"),
        SHEET("底部弹窗"),
    }

    private val _layout = MutableStateFlow(HomeLayout.GRID_4)
    val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

    // v0.188.0：换源器 6 种布局（0 大卡 1 图标 2 列表 3 宽卡 4 中卡 5 紧凑），
    // 与首页布局同机制持久化，关弹窗重开不再回默认大卡。
    private val _pickerLayout = MutableStateFlow(0)
    val pickerLayout: StateFlow<Int> = _pickerLayout.asStateFlow()

    // v1.1.25：换源器容器形态，默认悬浮窗。
    private val _pickerMode = MutableStateFlow(PickerMode.FLOAT)
    val pickerMode: StateFlow<PickerMode> = _pickerMode.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOME_LAYOUT, null)
        _layout.value = runCatching { HomeLayout.valueOf(name!!) }
            .getOrDefault(HomeLayout.GRID_4)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _pickerLayout.value = prefs.getInt(KEY_SOURCE_PICKER_LAYOUT, 0).coerceIn(0, 5)
        _pickerMode.value = runCatching {
            PickerMode.valueOf(prefs.getString(KEY_PICKER_MODE, null)!!)
        }.getOrDefault(PickerMode.FLOAT)
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

    // v1.1.25：换源器容器形态切换——点切换按钮即换形态并持久化，
    // 下次打开直接用上次选的；写失败只记日志，不挡切换。
    fun setPickerMode(context: Context, mode: PickerMode) {
        _pickerMode.value = mode
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PICKER_MODE, mode.name)
                .apply()
        }.onFailure { LogBus.w("HomeSettings", "换源器形态持久化失败：${it.message}") }
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
