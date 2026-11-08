package com.movieshell.core.net

import org.json.JSONObject
import java.net.URLEncoder

/**
 * v1.1.6 分层第 2 批：搜索请求（URL + 方法 + 表单体 + 编码 + 附加头）。
 * 原 BookRuleEngine.SearchRequest / buildSearchRequest / parseUrlOptions，
 * 一字未改，只换包——CSS 搜索链与 JS 引擎的 fetchRaw 共用，放 core/net。
 */
data class SearchRequest(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val charsetName: String = "UTF-8",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * v0.197.0：解析 searchUrl。
 * - 无 `,{...}` 后缀：老行为，GET，{{key}} 按 UTF-8 编码。
 * - 有后缀：按 method/body/charset/headers 解析；body 里的 {{key}} 按 charset
 *   编码（如 GBK 站），URL 里的 {{key}} 仍按 UTF-8 编码；{{page}} 恒为 1
 *   （本引擎搜索只抓一页，不做分页）。
 * - 后缀 JSON 解析失败时回落老行为，不炸掉老源。
 */
fun buildSearchRequest(template: String, keyword: String): SearchRequest {
    val key = keyword.trim()
    val utf8Key = URLEncoder.encode(key, "UTF-8")
    val optStart = template.indexOf(",{")
    if (optStart < 0) {
        return SearchRequest(
            url = template.replace("{{key}}", utf8Key).replace("{{page}}", "1"),
        )
    }
    val opts = runCatching { JSONObject(template.substring(optStart + 1)) }.getOrNull()
        ?: return SearchRequest(
            // 后缀不是合法 JSON：只取逗号前的纯 URL，不把垃圾后缀带进请求
            url = template.substring(0, optStart)
                .replace("{{key}}", utf8Key).replace("{{page}}", "1"),
        )
    val charsetName = opts.optString("charset", "UTF-8").ifBlank { "UTF-8" }
    val encodedKey = runCatching { URLEncoder.encode(key, charsetName) }.getOrDefault(utf8Key)
    val headers = mutableMapOf<String, String>()
    opts.optJSONObject("headers")?.let { h ->
        h.keys().forEach { k -> headers[k] = h.optString(k) }
    }
    val body = opts.optString("body", "")
        .replace("{{key}}", encodedKey)
        .replace("{{page}}", "1")
        .ifBlank { null }
    return SearchRequest(
        url = template.substring(0, optStart)
            .replace("{{key}}", utf8Key).replace("{{page}}", "1"),
        method = opts.optString("method", "GET").ifBlank { "GET" }.uppercase(),
        body = body,
        charsetName = charsetName,
        headers = headers,
    )
}

/**
 * 288：目录 POST 支持——解析 `URL,{"method":"POST","body":"...","charset":"GBK","headers":{...}}`
 * 这种阅读式选项后缀（与 197 的 searchUrl 同一套语法），不带后缀的老目录 URL
 * 原样走 GET，不受影响。body 不做 {{key}} 这类占位替换，目录 POST 的表单
 * 体本来就是规则里写死的（如 id=123）；后缀 JSON 非法时回落干净 URL 的 GET。
 */
fun parseUrlOptions(template: String): SearchRequest {
    val optStart = template.indexOf(",{")
    if (optStart < 0) return SearchRequest(url = template)
    val opts = runCatching { JSONObject(template.substring(optStart + 1)) }.getOrNull()
        ?: return SearchRequest(url = template.substring(0, optStart))
    val charsetName = opts.optString("charset", "UTF-8").ifBlank { "UTF-8" }
    val headers = mutableMapOf<String, String>()
    opts.optJSONObject("headers")?.let { h ->
        h.keys().forEach { k -> headers[k] = h.optString(k) }
    }
    return SearchRequest(
        url = template.substring(0, optStart),
        method = opts.optString("method", "GET").ifBlank { "GET" }.uppercase(),
        body = opts.optString("body", "").ifBlank { null },
        charsetName = charsetName,
        headers = headers,
    )
}
