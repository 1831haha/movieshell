package com.movieshell.data.source

import com.movieshell.data.model.ParseConfig
import com.movieshell.data.model.SiteConfig
import com.movieshell.data.model.TvBoxConfig
import com.movieshell.data.model.WarehouseEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * TVBox 标准订阅 JSON 解析器。
 *
 * 不走严格的 @Serializable 反序列化，而是手工逐字段容忍式提取，
 * 因为真实订阅源里常见：字段缺失、type 写成字符串 "1"、searchable 写成 true/false、
 * 多余未知字段。任何单条 site 解析失败只跳过该条，不影响整体。
 */
object TvBoxConfigParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** 解析订阅 JSON，失败（非 JSON / 顶层无 sites）返回空配置，不抛异常。 */
    fun parseConfig(raw: String): TvBoxConfig {
        return try {
            // v0.11.6：订阅 JSON 可能是带 // 注释 / BOM 的宽松格式，先清洗
            val clean = stripJsonComments(raw.trim().removePrefix("\uFEFF"))
            val root = json.parseToJsonElement(clean).jsonObject
            TvBoxConfig(
                sites = root["sites"]?.asArray().orEmpty()
                    .mapNotNull { runCatching { it.jsonObject.toSiteConfig() }.getOrNull() },
                parses = root["parses"]?.asArray().orEmpty()
                    .mapNotNull { runCatching { it.jsonObject.toParseConfig() }.getOrNull() },
                flags = root["flags"]?.asArray().orEmpty()
                    .mapNotNull { it.asStringOrNull() },
                spider = root["spider"].asStringOrNull().orEmpty()
            )
        } catch (_: Exception) {
            TvBoxConfig()
        }
    }

    /**
     * v0.43.0：多仓（子仓列表）解析。
     * 两种形态：
     * 1. JSON：{"urls":[{"name":"...","url":"..."}]}（小盒子系多仓；BOM 与行注释已清洗）。
     * 2. TXT：每行一个子仓，支持 url、name,url、name$url 三种写法。
     * 命中任一形态返回非空列表；否则返回 null，调用方继续走普通订阅解析。
     */
    fun parseWarehouse(raw: String): List<WarehouseEntry>? {
        val clean = raw.trim().removePrefix("\uFEFF")
        if (clean.isEmpty()) return null
        if (clean.startsWith("{")) {
            return runCatching {
                val root = json.parseToJsonElement(stripJsonComments(clean)).jsonObject
                // 带 sites 的是普通订阅，不是多仓
                if (!root["sites"].asArray().isNullOrEmpty()) return null
                root["urls"].asArray()
                    ?.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        val url = o["url"].asStringOrNull().orEmpty().trim()
                        if (url.isEmpty()) return@mapNotNull null
                        WarehouseEntry(
                            name = o["name"].asStringOrNull().orEmpty().trim()
                                .ifBlank { hostOf(url) },
                            url = url,
                        )
                    }
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
        val list = clean.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }
            .mapNotNull { line ->
                // v0.110.0：分隔符取 $ 与 , 中首次出现位置更靠左的；只含一种时退化为旧行为；
                // 两种都不含则整行当 URL（名字用域名兜底）。旧逻辑无条件优先 $，URL 查询串里
                // 带 $ 时会把有效子仓行切废（如 名字,http://host/x?a$b 会被整行丢弃）。
                val dollarAt = line.indexOf('$')
                val commaAt = line.indexOf(',')
                val sep: String? = when {
                    dollarAt >= 0 && (commaAt < 0 || dollarAt < commaAt) -> "\$"
                    commaAt >= 0 -> ","
                    else -> null
                }
                val name = if (sep == null) "" else line.substringBefore(sep).trim()
                val url = if (sep == null) line else line.substringAfter(sep).trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return@mapNotNull null
                }
                WarehouseEntry(name = name.ifBlank { hostOf(url) }, url = url)
            }
            .toList()
        return list.takeIf { it.isNotEmpty() }
    }

    /** 子仓名称缺失时用域名兜底。 */
    private fun hostOf(url: String): String = runCatching {
        url.substringAfter("://").substringBefore("/").substringBefore(":")
    }.getOrDefault("子仓").ifBlank { "子仓" }

    /**
     * 去掉 JSON 中的 // 行注释和块注释（字符串内的不碰）。
     * 纯函数，可单测。
     */
    fun stripJsonComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inStr = false
        var esc = false
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                out.append(c)
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                i++
            } else if (c == '"') {
                inStr = true
                out.append(c)
                i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun JsonObject.toSiteConfig(): SiteConfig = SiteConfig(
        key = this["key"].asStringOrNull().orEmpty(),
        name = this["name"].asStringOrNull().orEmpty(),
        type = this["type"].asIntOrNull() ?: 1,
        api = this["api"].asStringOrNull().orEmpty(),
        searchable = this["searchable"].asIntOrNull() ?: 1,
        quickSearch = this["quickSearch"].asIntOrNull() ?: 0,
        filterable = this["filterable"].asIntOrNull() ?: 0,
        ext = when (val e = this["ext"]) {
            // v0.11.3：ext 是对象时（如 csp_AppGet 的配置）序列化成 JSON 字符串保留，
            // 否则 spider.init 拿不到配置
            is JsonObject -> e.toString()
            is JsonArray -> e.toString()
            else -> e.asStringOrNull().orEmpty()
        },
        jar = this["jar"].asStringOrNull().orEmpty(),
        // 扩展字段"类型"：小说/漫画站用它标识（勿用 categories），规整化到 Site.typeLabel
        typeLabel = this["类型"].asStringOrNull().orEmpty(),
        // 内部字段：聚合搜索拼装配置时写入，spider 站定位 jar 用
        subUrl = this["subUrl"].asStringOrNull().orEmpty()
    )

    private fun JsonObject.toParseConfig(): ParseConfig {
        // 真实订阅里 ext 可能是字符串，也可能是对象 {"flag":[...],"header":{"User-Agent":"..."}}：
        // 对象形态拆成 header/flags 字段，ext 只保留字符串形态（对象形态时 ext 留空）。
        val extEl = this["ext"]
        var extStr = ""
        val flags = mutableListOf<String>()
        val header = mutableMapOf<String, String>()
        if (extEl is JsonObject) {
            when (val flagEl = extEl["flag"]) {
                is JsonArray -> flagEl.forEach {
                    (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() }
                        ?.let { s -> flags += s }
                }
                is JsonPrimitive -> flagEl.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { flags += it }
                else -> { /* JsonObject/JsonNull 等非常规形态忽略 */ }
            }
            (extEl["header"] as? JsonObject)?.entries?.forEach { (k, v) ->
                val value = (v as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (k.isNotBlank() && value.isNotEmpty()) header[k] = value
            }
        } else {
            extStr = extEl.asStringOrNull().orEmpty()
        }
        return ParseConfig(
            name = this["name"].asStringOrNull().orEmpty(),
            url = this["url"].asStringOrNull().orEmpty(),
            type = this["type"].asIntOrNull() ?: 0,
            ext = extStr,
            header = header,
            flags = flags
        )
    }

    // ---- 容忍式取值 helpers ----

    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

    /** 字符串 / 数字 / 布尔 统一转字符串；JsonNull 与缺失返回 null。 */
    private fun JsonElement?.asStringOrNull(): String? {
        val prim = (this as? JsonPrimitive) ?: return null
        if (prim is JsonNull) return null
        return prim.contentOrNull
    }

    /** 兼容 Int / "1" / true / 1.0 等写法。 */
    private fun JsonElement?.asIntOrNull(): Int? {
        val prim = (this as? JsonPrimitive) ?: return null
        if (prim is JsonNull) return null
        prim.intOrNull?.let { return it }
        prim.booleanOrNull?.let { return if (it) 1 else 0 }
        return prim.contentOrNull?.toDoubleOrNull()?.toInt()
    }
}
