package com.movieshell.data.repo

import android.content.Context

/**
 * v0.217.0（F57）：删源清理的唯一入口——站源页删除与插件市场卸载共用。
 * 原来两条路径各写一套，市场卸载只清内存激活态，禁用态／探活／首页
 * 缓存／置顶全残留，同名源重装后旧状态复活。六项（与站源页原有口径一致）：
 * ①内存激活态（仅当删的正是当前激活才清）②持久化激活记录
 * ③禁用态 ④探活徽标 ⑤首页缓存 ⑥置顶。
 * web 站无本地规则管理器与首页缓存，只走③④⑥与激活站点清理。
 */
object SourceDeleteCleanup {

    /** 置顶持久化（站源页 loadPins/savePins 同文件同 key，勿另起）。 */
    const val PIN_PREFS = "sources_pin"

    fun run(context: Context, kind: String, fileName: String) {
        when (kind) {
            "js" -> if (DrpySourceManager.activeRuleName() == fileName) {
                DrpySourceManager.setActiveRule(null)
                SourceStateStore.clearActiveRule(context)
            }
            "py" -> if (PySourceManager.activeRuleName() == fileName) {
                PySourceManager.setActiveRule(null)
                SourceStateStore.clearActiveRule(context)
            }
            "php" -> if (PhpSourceManager.activeRuleName() == fileName) {
                PhpSourceManager.setActiveRule(null)
                SourceStateStore.clearActiveRule(context)
            }
            "web" -> if (WebSourceManager.activeSiteName() == fileName) {
                WebSourceManager.setActiveSite(null)
            }
        }
        // v0.186.0 M20：删源清理之禁用态与探活；同名文件日后重新导入，
        // 旧禁用态与旧探活徽标不再复活。
        SourceStateStore.removeDisabled(context, kind, fileName)
        SourceProbeStore.removeProbe(context, kind, fileName)
        // v0.215.0：删源清理之首页缓存——同名源重装后不再先读到旧缓存。
        // v0.218.0（S5）：内存失败计数同款清理，否则删源重装同名源会
        // 接着旧 streak 走更长退避。
        when (kind) {
            "js" -> {
                HomeCache.clear(context, "drpy_$fileName")
                HomeLoadGate.clearStreak("drpy_$fileName")
            }
            "py" -> {
                HomeCache.clear(context, "py_$fileName")
                HomeLoadGate.clearStreak("py_$fileName")
            }
            "php" -> {
                HomeCache.clear(context, "php_$fileName")
                HomeLoadGate.clearStreak("php_$fileName")
            }
        }
        // 置顶记录同步清理（持久化；站源页内存 map 由刷新时重读）。
        val prefs = context.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE)
        val pins = prefs.getString(kind, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }
        if (fileName in pins) {
            prefs.edit()
                .putString(kind, pins.filterNot { it == fileName }.joinToString("\n"))
                .apply()
        }
    }
}
