package com.movieshell.data.source

import com.movieshell.data.model.ParseConfig
import com.movieshell.data.model.SiteConfig
import com.movieshell.data.model.TvBoxConfig
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
            val root = json.parseToJsonElement(raw).jsonObject
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
