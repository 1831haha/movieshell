package com.movieshell.data.repo

import android.content.Context

/**
 * v0.63.0 下载自动清理设置（download_prefs）。
 * - autoDeleteWatched：看完自动删文件（播放进度≥95% 且为本地下载文件）
 * - autoClearDays：N 天前的已完成任务自动清理（0=不清理）
 * - lowSpaceWarn：下载前空间不足提醒
 */
object DownloadSettings {

    private const val PREFS = "download_prefs"
    private const val KEY_AUTO_DELETE_WATCHED = "dl_auto_delete_watched"
    private const val KEY_AUTO_CLEAR_DAYS = "dl_auto_clear_days"
    private const val KEY_LOW_SPACE_WARN = "dl_low_space_warn"

    /** 空间不足阈值：可用空间低于此值提醒（500MB）。 */
    const val LOW_SPACE_BYTES = 500L * 1024 * 1024

    fun autoDeleteWatched(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_DELETE_WATCHED, false)

    fun setAutoDeleteWatched(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_DELETE_WATCHED, v).apply()
    }

    fun autoClearDays(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_CLEAR_DAYS, 7).coerceIn(0, 30)

    fun setAutoClearDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_AUTO_CLEAR_DAYS, days.coerceIn(0, 30)).apply()
    }

    fun lowSpaceWarn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOW_SPACE_WARN, true)

    fun setLowSpaceWarn(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOW_SPACE_WARN, v).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
