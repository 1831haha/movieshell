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
        ext = this["ext"].asStringOrNull().orEmpty(),
        jar = this["jar"].asStringOrNull().orEmpty()
    )

    private fun JsonObject.toParseConfig(): ParseConfig = ParseConfig(
        name = this["name"].asStringOrNull().orEmpty(),
        url = this["url"].asStringOrNull().orEmpty(),
        type = this["type"].asIntOrNull() ?: 0,
        ext = this["ext"].asStringOrNull().orEmpty()
    )

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
