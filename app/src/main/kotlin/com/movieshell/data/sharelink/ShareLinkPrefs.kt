package com.movieshell.data.sharelink

import android.content.Context

/**
 * v1.3.7：分享链接播剧页“上次的链接”持久化（本机 SharedPreferences，只存本机）。
 * 口径照抄 EmbyStore：getSharedPreferences + apply()。
 */
object ShareLinkPrefs {
    private const val PREFS = "sharelink_prefs"

    /** 上次解析的分享链接（进页自动填回输入框）。 */
    const val KEY_LAST_URL = "sharelink_last_url"

    fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_URL, null).orEmpty()

    fun save(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_URL, url).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_URL).apply()
    }
}
