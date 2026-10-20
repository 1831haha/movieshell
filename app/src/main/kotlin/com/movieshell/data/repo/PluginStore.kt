package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/**
 * 插件市场本地状态（v0.23.0）。
 * - marketSources：可增删的多市场源列表（默认"侠影官方市场"），全部 enabled 的市场归并展示。
 * - showIncompatible：是否显示侠影不支持的插件（apk/import 等，仅查看不可安装）。
 * - 已安装插件记录：id → InstalledPlugin（JSON 持久化），用于"已安装/有新版"判定与卸载删文件。
 */
object PluginStore {

    const val DEFAULT_MARKET_NAME = "侠影官方市场"
    const val DEFAULT_MARKET_URL =
        "https://raw.githubusercontent.com/1831haha/xiaying-plugins/main/market.json"

    /** DsPlayer 官方市场（实验性：source/server/live 可跨装，apk/import 自动隐藏）。用户在设置页手动添加。 */
    const val DSPLAYER_MARKET_URL =
        "https://raw.githubusercontent.com/hjdhnx/dsplayer-plugin/main/market.json"

    private const val PREFS = "plugin_prefs"
    private const val KEY_MARKET_URL = "market_url" // v0.22.0 单地址，迁移用
    private const val KEY_SOURCES = "market_sources_json"
    private const val KEY_SHOW_INCOMPAT = "show_incompatible"
    private const val KEY_INSTALLED = "installed_json"
    private const val KEY_GITHUB_PROXY = "github_proxy"

    data class MarketSource(
        val id: String,
        val name: String,
        val url: String,
        val enabled: Boolean = true,
    )

    data class InstalledPlugin(
        val id: String,
        val name: String,
        val version: String,
        val type: String,
        val engine: String,
        /** 已安装的文件：source 为规则目录下文件名；server 为 plugins/<id>/ 下相对路径；live 为 live/<id>.json */
        val files: List<String>,
    )

    private val _marketSources = MutableStateFlow(
        listOf(MarketSource(deriveId(DEFAULT_MARKET_URL), DEFAULT_MARKET_NAME, DEFAULT_MARKET_URL)),
    )
    val marketSources: StateFlow<List<MarketSource>> = _marketSources

    private val _showIncompatible = MutableStateFlow(false)
    val showIncompatible: StateFlow<Boolean> = _showIncompatible

    private val _githubProxy = MutableStateFlow("")
    val githubProxy: StateFlow<String> = _githubProxy

    private val installed = mutableMapOf<String, InstalledPlugin>()
    private var inited = false

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _marketSources.value = readSources(prefs.getString(KEY_SOURCES, null))
            ?: migrateSingle(prefs.getString(KEY_MARKET_URL, null))
        _showIncompatible.value = prefs.getBoolean(KEY_SHOW_INCOMPAT, false)
        _githubProxy.value = prefs.getString(KEY_GITHUB_PROXY, "").orEmpty()
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

    /** id 派生：官方地址固定 "official"，其余 "src-" + MD5(url) 前 10 位 hex。 */
    private fun deriveId(url: String): String {
        if (url == DEFAULT_MARKET_URL) return "official"
        val hex = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return "src-" + hex.take(10)
    }

    private fun readSources(json: String?): List<MarketSource>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val url = o.getString("url")
                MarketSource(
                    id = o.optString("id").ifBlank { deriveId(url) },
                    name = o.getString("name"),
                    url = url,
                    enabled = o.optBoolean("enabled", true),
                )
            }.filter { it.name.isNotBlank() && it.url.isNotBlank() }
                .ifEmpty { null }
        }.getOrNull()
    }

    /** v0.22.0 单地址迁移：官方地址→默认源；自定义地址→追加为"自定义市场"。 */
    private fun migrateSingle(oldUrl: String?): List<MarketSource> {
        val list = mutableListOf(
            MarketSource(deriveId(DEFAULT_MARKET_URL), DEFAULT_MARKET_NAME, DEFAULT_MARKET_URL),
        )
        val clean = oldUrl.orEmpty().trim()
        if (clean.isNotBlank() && clean != DEFAULT_MARKET_URL) {
            list.add(MarketSource(deriveId(clean), "自定义市场", clean))
        }
        return list
    }

    private fun persistSources(context: Context) {
        val arr = JSONArray()
        _marketSources.value.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("enabled", it.enabled),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCES, arr.toString())
            .apply()
    }

    fun addMarketSource(context: Context, name: String, url: String): Boolean {
        val n = name.trim().ifBlank { return false }
        val u = url.trim().ifBlank { return false }
        if (_marketSources.value.any { it.url == u }) return false
        _marketSources.value = _marketSources.value + MarketSource(deriveId(u), n, u)
        persistSources(context)
        return true
    }

    fun updateMarketSource(context: Context, id: String, name: String, url: String): Boolean {
        val n = name.trim().ifBlank { return false }
        val u = url.trim().ifBlank { return false }
        if (_marketSources.value.any { it.id != id && it.url == u }) return false
        val idx = _marketSources.value.indexOfFirst { it.id == id }
        if (idx < 0) return false
        _marketSources.value = _marketSources.value.mapIndexed { i, s ->
            if (i == idx) s.copy(name = n, url = u) else s
        }
        persistSources(context)
        return true
    }

    fun removeMarketSource(context: Context, id: String) {
        _marketSources.value = _marketSources.value.filterNot { it.id == id }
        persistSources(context)
    }

    fun setMarketEnabled(context: Context, id: String, enabled: Boolean) {
        _marketSources.value = _marketSources.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        persistSources(context)
    }

    fun nameOfSource(url: String): String? =
        _marketSources.value.firstOrNull { it.url == url }?.name

    fun setGithubProxy(context: Context, prefix: String) {
        val clean = prefix.trim()
        _githubProxy.value = clean
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_GITHUB_PROXY, clean).apply()
    }

    /** GitHub 加速前缀单一入口：仅 raw.githubusercontent.com / github.com 域名拼接，其余原样。 */
    fun applyGithubProxy(url: String): String {
        val prefix = _githubProxy.value.trim()
        if (prefix.isBlank()) return url
        val host = runCatching { URI(url).host }.getOrNull() ?: return url
        if (host != "raw.githubusercontent.com" && host != "github.com") return url
        return if (prefix.endsWith("/")) prefix + url else "$prefix/$url"
    }

    fun setShowIncompatible(context: Context, show: Boolean) {
        _showIncompatible.value = show
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOW_INCOMPAT, show).apply()
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
