package com.movieshell.data.source

import android.content.Context
import com.movieshell.data.repo.LogBus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v0.294.7（M6）：订阅顶层 `rules` / `ads`（FongMi 口径）接入嗅探与去广告。
 *
 * `rules`：`[{"name":..,"hosts":["a.com"],"regex":["\\.m3u8"],"exclude":["\\.css"]}]`
 * （兼容 `host` 字符串、`rule` 作 regex 别名；`*` 表示所有域名）。命中 host 且匹配 regex
 * 的请求当直链；匹配 exclude 的一律不当直链。
 * `ads`：广告域名列表，嗅探 WebView 直接拦截、m3u8 分片 URL 命中即视为广告信号。
 */
@Serializable
data class SniffHostRule(
    val name: String = "",
    val hosts: List<String> = emptyList(),
    val regex: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)

object SniffRules {

    private class Compiled(val hosts: List<String>, val regex: List<Regex>, val exclude: List<Regex>)

    @Volatile private var compiled: List<Compiled> = emptyList()
    @Volatile private var adHosts: List<String> = emptyList()
    @Volatile private var dirty = true

    /** 纯函数：订阅 JSON `rules` 数组 → 规则列表（容错，坏项跳过）。 */
    fun parseRules(el: JsonElement?): List<SniffHostRule> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            fun strList(key: String): List<String> = when (val v = o[key]) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                is JsonPrimitive -> listOfNotNull(v.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
                else -> emptyList()
            }
            val hosts = (strList("hosts") + strList("host")).distinct()
            val regex = (strList("regex") + strList("rule")).distinct()
            val exclude = strList("exclude")
            if (hosts.isEmpty() || (regex.isEmpty() && exclude.isEmpty())) null
            else SniffHostRule(
                name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                hosts = hosts, regex = regex, exclude = exclude,
            )
        }
    }

    /** 纯函数：订阅 JSON `ads` 数组 → 域名列表。 */
    fun parseAds(el: JsonElement?): List<String> =
        (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() } }
            ?.distinct().orEmpty()

    fun set(rules: List<SniffHostRule>, ads: List<String>) {
        compiled = rules.map { r ->
            Compiled(
                hosts = r.hosts.map { it.lowercase() },
                regex = r.regex.mapNotNull { runCatching { it.toRegex(RegexOption.IGNORE_CASE) }.getOrNull() },
                exclude = r.exclude.mapNotNull { runCatching { it.toRegex(RegexOption.IGNORE_CASE) }.getOrNull() },
            )
        }
        adHosts = ads.map { it.lowercase() }.distinct()
        dirty = false
    }

    /** 订阅刷新/删除后调用，下次用到时重读。 */
    fun invalidate() { dirty = true }

    /** 从全部订阅缓存汇总（懒加载；读失败保持空规则，不影响原嗅探）。 */
    fun ensureLoaded(context: Context) {
        if (!dirty) return
        runCatching {
            val (rules, ads) = com.movieshell.data.repo.SubSiteStore.allSniffRules(context)
            set(rules, ads)
            if (rules.isNotEmpty() || ads.isNotEmpty()) {
                LogBus.i("SniffRules", "订阅嗅探规则 ${rules.size} 条、广告域名 ${ads.size} 个")
            }
        }.onFailure { dirty = false }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

    private fun hostMatches(ruleHost: String, host: String): Boolean =
        ruleHost == "*" || (host.isNotEmpty() && host.contains(ruleHost))

    /** true=按订阅规则是直链；false=被订阅 exclude 排除；null=无规则命中。 */
    fun match(url: String): Boolean? {
        val list = compiled
        if (list.isEmpty()) return null
        val host = hostOf(url)
        var positive = false
        for (r in list) {
            if (r.hosts.none { hostMatches(it, host) }) continue
            if (r.exclude.any { it.containsMatchIn(url) }) return false
            if (r.regex.any { it.containsMatchIn(url) }) positive = true
        }
        return if (positive) true else null
    }

    fun isAdHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.isEmpty()) return false
        return adHosts.any { h == it || h.endsWith(".$it") || h.contains(it) }
    }

    fun isAdUrl(url: String): Boolean = isAdHost(hostOf(url))
}
