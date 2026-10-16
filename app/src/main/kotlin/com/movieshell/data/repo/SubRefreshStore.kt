package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 订阅自动刷新状态（v0.46.0）。
 *
 * - 开关：默认开，设置页可关。
 * - 每次后台刷新后记：最后检查时间 + 是否成功。订阅管理页据此标"已失效"。
 */
object SubRefreshStore {
    private const val TAG = "SubRefresh"
    private const val PREFS = "sub_refresh_store"
    private const val KEY_ENABLED = "auto_refresh_enabled"
    private const val KEY_STATUS = "status_json"

    /** 单订阅刷新状态：ok=true 成功；false=失败（标"已失效"）。 */
    @kotlinx.serialization.Serializable
    data class SubStatus(val checkedAt: Long = 0L, val ok: Boolean = true)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun recordResult(context: Context, url: String, ok: Boolean) {
        val all = loadAll(context).toMutableMap()
        all[url] = SubStatus(System.currentTimeMillis(), ok)
        saveAll(context, all)
    }

    fun statusOf(context: Context, url: String): SubStatus? = loadAll(context)[url]

    private fun loadAll(context: Context): Map<String, SubStatus> = try {
        prefs(context).getString(KEY_STATUS, null)
            ?.let { Json.decodeFromString<Map<String, SubStatus>>(it) }
            .orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "读刷新状态失败", e)
        emptyMap()
    }

    private fun saveAll(context: Context, all: Map<String, SubStatus>) {
        // 只留最近 100 条，防无限膨胀
        val trimmed = all.entries
            .sortedByDescending { it.value.checkedAt }
            .take(100)
            .associate { it.key to it.value }
        prefs(context).edit()
            .putString(KEY_STATUS, Json.encodeToString(trimmed))
            .apply()
    }
}
