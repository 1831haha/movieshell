package com.movieshell.data.sharelink

import android.content.Context

/**
 * v1.3.10：分享链接播剧页“历史记录”持久化（本机 SharedPreferences，只存本机）。
 * 口径照抄 EmbyStore：getSharedPreferences + apply()。
 * v1.3.7 的“上次链接自动填回输入框”用户不要了（2026-11-18 亲令：
 * 历史记录留在历史记录里，输入框进页是空的），KEY_LAST_URL 已废弃
 * （旧值还躺在用户本机 prefs 里，但再也不会被读到），历史记录改用
 * KEY_HISTORY 存（多行文本，一行一条链接，最多 10 条，最新在前）。
 */
object ShareLinkPrefs {
    private const val PREFS = "sharelink_prefs"

    /** 历史记录：一行一条链接，最多 MAX_HISTORY 条，最新在前。 */
    const val KEY_HISTORY = "sharelink_history"
    const val MAX_HISTORY = 10

    /** 历史记录（最新在前）。链接里不会有换行，一行一条够用了。 */
    fun loadHistory(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null).orEmpty()
            .split("\n").filter { it.isNotBlank() }

    /** 解析成功后记一条：去重、最新放最前、只留 10 条。 */
    fun addHistory(context: Context, url: String) {
        val updated = mergeHistory(loadHistory(context), url)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, updated.joinToString("\n")).apply()
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }

    /** 删历史里的一条（点行尾 ×）。 */
    fun removeHistory(context: Context, url: String) {
        val updated = dropHistory(loadHistory(context), url)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, updated.joinToString("\n")).apply()
    }

    /** 删一条的纯逻辑（load/remove 要走真 SharedPreferences，纯 JUnit 测它）。 */
    fun dropHistory(existing: List<String>, url: String): List<String> =
        existing.filter { it != url.trim() }

    /** 历史记录的纯拼装逻辑（load/add 要走真 SharedPreferences，纯 JUnit 测它）。 */
    fun mergeHistory(existing: List<String>, url: String): List<String> {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return existing
        return (listOf(trimmed) + existing.filter { it != trimmed }).take(MAX_HISTORY)
    }
}
