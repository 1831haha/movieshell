package com.movieshell.player

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull

/**
 * v0.294.7：播放请求头工具（纯函数，可单测）。
 *
 * - [canonicalKey]：常见头名规整成标准大小写（user-agent → User-Agent），
 *   播放器按 "User-Agent" 单独取 UA，规则给小写键时不再被默认 UA 顶掉。
 * - [normalize]：去空键空值、同名（忽略大小写）只留后出现的一条、键名规整。
 * - [merge]：忽略大小写合并，[primary] 优先、[fallback] 只补缺。
 * - [fromJson]：规则 header 可能是对象、可能是 JSON 字符串、值可能是数字/布尔，统一转 Map。
 */
object PlayHeaders {

    private val CANONICAL = listOf(
        "User-Agent", "Referer", "Cookie", "Origin", "Accept", "Accept-Language",
        "Accept-Encoding", "Authorization", "Range", "Host", "X-Requested-With",
    ).associateBy { it.lowercase() }

    fun canonicalKey(key: String): String {
        val k = key.trim()
        CANONICAL[k.lowercase()]?.let { return it }
        // referrer 常见笔误
        if (k.equals("referrer", ignoreCase = true)) return "Referer"
        if (k.equals("ua", ignoreCase = true)) return "User-Agent"
        return k
    }

    fun normalize(headers: Map<String, String>?): Map<String, String> {
        if (headers.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k0, v0) in headers) {
            val k = canonicalKey(k0)
            val v = v0.trim()
            if (k.isEmpty() || v.isEmpty()) continue
            // 同名不同大小写：删旧键再放，保留后出现的值
            out.keys.firstOrNull { it.equals(k, ignoreCase = true) }?.let { out.remove(it) }
            out[k] = v
        }
        return out
    }

    /** 忽略大小写合并：primary 的键全部保留，fallback 只补 primary 没有的键。 */
    fun merge(primary: Map<String, String>?, fallback: Map<String, String>?): Map<String, String> {
        val p = normalize(primary)
        val f = normalize(fallback)
        if (f.isEmpty()) return p
        if (p.isEmpty()) return f
        val out = LinkedHashMap(p)
        for ((k, v) in f) {
            if (out.keys.none { it.equals(k, ignoreCase = true) }) out[k] = v
        }
        return out
    }

    fun get(headers: Map<String, String>?, key: String): String? =
        headers?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** header 字段容错：对象 / JSON 字符串 / 非字符串值都认；解析失败返回空。 */
    fun fromJson(el: JsonElement?): Map<String, String> {
        val obj: JsonObject = when (el) {
            null, is JsonNull -> return emptyMap()
            is JsonObject -> el
            is JsonPrimitive -> {
                val s = el.contentOrNull?.trim().orEmpty()
                if (!s.startsWith("{")) return emptyMap()
                runCatching { json.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: return emptyMap()
            }
            else -> return emptyMap()
        }
        val raw = LinkedHashMap<String, String>()
        for ((k, v) in obj) {
            val value = when (v) {
                is JsonPrimitive -> v.contentOrNull
                is JsonNull -> null
                else -> v.toString()
            }?.trim().orEmpty()
            if (value.isNotEmpty()) raw[k] = value
        }
        return normalize(raw)
    }
}
