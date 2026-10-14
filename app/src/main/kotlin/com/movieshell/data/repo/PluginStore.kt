package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件市场本地状态（v0.22.0）。
 * - marketUrl：市场地址（设置页可改），存在 plugin_prefs。
 * - 已安装插件记录：id → InstalledPlugin（JSON 持久化），用于"已安装/有新版"判定与卸载删文件。
 */
object PluginStore {

    const val DEFAULT_MARKET_URL =
        "https://raw.githubusercontent.com/1831haha/xiaying-plugins/main/market.json"

    private const val PREFS = "plugin_prefs"
    private const val KEY_MARKET_URL = "market_url"
    private const val KEY_INSTALLED = "installed_json"

    data class InstalledPlugin(
        val id: String,
        val name: String,
        val version: String,
        val type: String,
        val engine: String,
        /** 已安装的文件：source 插件为规则目录下的文件名；server 插件为 plugins/<id>/ 下相对路径 */
        val files: List<String>,
    )

    private val _marketUrl = MutableStateFlow(DEFAULT_MARKET_URL)
    val marketUrl: StateFlow<String> = _marketUrl

    private val installed = mutableMapOf<String, InstalledPlugin>()
    private var inited = false

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _marketUrl.value = prefs.getString(KEY_MARKET_URL, DEFAULT_MARKET_URL)
            .orEmpty().ifBlank { DEFAULT_MARKET_URL }
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_INSTALLED, "[]").orEmpty().ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val files = mutableListOf<String>()
                val fa = o.optJSONArray("files")
                if (fa != null) for (j in 0 until fa.length()) files.add(fa.getString(j))
                installed[o.getString("id")] = InstalledPlugin(
                    id = o.getString("id"),
                    name = o.optString("name", o.getString("id")),
                    version = o.optString("version", ""),
                    type = o.optString("type", ""),
                    engine = o.optString("engine", ""),
                    files = files,
                )
            }
        }
    }

    fun setMarketUrl(context: Context, url: String) {
        val clean = url.trim().ifBlank { DEFAULT_MARKET_URL }
        _marketUrl.value = clean
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MARKET_URL, clean).apply()
    }

    @Synchronized
    fun listInstalled(): List<InstalledPlugin> = installed.values.toList()

    @Synchronized
    fun installedOf(id: String): InstalledPlugin? = installed[id]

    @Synchronized
    fun recordInstalled(context: Context, plugin: InstalledPlugin) {
        installed[plugin.id] = plugin
        persist(context)
    }

    @Synchronized
    fun removeInstalled(context: Context, id: String) {
        installed.remove(id)
        persist(context)
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        installed.values.forEach { p ->
            val o = JSONObject()
                .put("id", p.id).put("name", p.name).put("version", p.version)
                .put("type", p.type).put("engine", p.engine)
            val fa = JSONArray()
            p.files.forEach { fa.put(it) }
            o.put("files", fa)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_INSTALLED, arr.toString()).apply()
    }
}
