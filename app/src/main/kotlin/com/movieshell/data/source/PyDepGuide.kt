package com.movieshell.data.source

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v0.27.2：PY 缺库引导。
 *
 * PY 规则因缺 Python 模块失败（ModuleNotFoundError: No module named 'xxx'）时，
 * 弹一次引导装「PY 引擎依赖包」（py-deps），一键安装。装过 / 点过"下次再说"就不再打扰。
 *
 * 触发点：PyApi.envelope()（首页/详情/播放/搜索/分类全链路的统一失败出口）。
 * 展示点：MainActivity 全局覆盖层。
 */
object PyDepGuide {

    /** 依赖包在市场的 id。 */
    const val DEP_PLUGIN_ID = "py-deps"

    private const val PREFS = "py_dep_guide"
    private const val KEY_PREFIX = "dismissed_"

    private val _missingModule = MutableStateFlow<String?>(null)
    val missingModule: StateFlow<String?> = _missingModule

    private val moduleRegex = Regex("No module named '([A-Za-z0-9_]+)'")

    /** 从 PY 失败文本里提取缺失模块名，命中则挂起引导（已有挂起的不覆盖）。 */
    fun reportError(errText: String) {
        if (_missingModule.value != null) return
        val mod = moduleRegex.find(errText)?.groupValues?.getOrNull(1) ?: return
        _missingModule.value = mod
    }

    fun clear() {
        _missingModule.value = null
    }

    /** 是否打扰过：已安装依赖包，或用户对该模块点过"下次再说"。 */
    fun shouldBother(context: Context, module: String): Boolean {
        if (com.movieshell.data.repo.PluginStore.installedOf(DEP_PLUGIN_ID) != null) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFIX + module, false)
    }

    fun markDismissed(context: Context, module: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PREFIX + module, true).apply()
        clear()
    }
}
