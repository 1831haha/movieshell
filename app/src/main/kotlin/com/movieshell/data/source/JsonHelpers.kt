package com.movieshell.data.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Type4Api / CmsApi 共用的 JSON 工具函数。
 *
 * v0.110.0：两处各有一份签名+语义完全一致的拷贝，抽到这里做单一入口。
 * 只收录真重复的；有细微差异的（如 Type4Api.fixPic、两处 safe/getText 的实例依赖）
 * 不硬抽，宁可留着。
 * v0.112.0：PyApi 的私有 stripHtml 也并入此处（正则统一为 `[^>]*`，
 * 与 PyApi 原版同语义，对空标签 `<>` 也清理，顶层声明覆盖后类内私有版本删除）。
 */

/** 兼容字段值为数字 / 布尔 / 字符串的写法；缺失或 null 返回空串。 */
fun JsonObject.optString(key: String): String {
    val prim = (this[key] as? JsonPrimitive) ?: return ""
    if (prim is JsonNull) return ""
    return prim.contentOrNull.orEmpty()
}

/** list 数组；缺失返回空数组。 */
fun JsonObject.listArray(): JsonArray =
    (this["list"] as? JsonArray) ?: JsonArray(emptyList())

/** 去 HTML 标签 + &nbsp;。 */
fun String.stripHtml(): String =
    replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
