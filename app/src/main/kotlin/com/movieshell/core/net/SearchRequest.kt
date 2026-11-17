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
 * v1.2.23：searchUrl 是否需要 JS 求值。
 * - `<js>` / `@js:` 前缀：整段是 JS（如 Lofter/123读书的 searchUrl）。
 * - `{{...}}` 表达式块：除 `{{key}}` / `{{page}}`（buildSearchRequest 直接替换）
 *   之外的块都要跑 JS（如读零零小说的 `{{url=source.getKey(); ...}}`）。
 * 纯 URL 或只含 `{{key}}`/`{{page}}` 的模板返回 false。
 */
fun searchUrlNeedsJs(template: String): Boolean {
    val t = template.trim()
    if (t.startsWith("<js>") || t.startsWith("@js:")) return true
    return jsTemplateBlocks(t).isNotEmpty()
}

/** v1.2.23：searchUrl 里除 {{key}}/{{page}} 之外的 {{...}} 表达式块内容。 */
fun jsTemplateBlocks(template: String): List<String> {
    val out = mutableListOf<String>()
    var idx = 0
    while (true) {
        val start = template.indexOf("{{", idx)
        if (start < 0) break
        val end = template.indexOf("}}", start + 2)
        if (end < 0) break
        val inner = template.substring(start + 2, end).trim()
        if (inner.isNotEmpty() && inner != "key" && inner != "page") out.add(inner)
        idx = end + 2
    }
    return out
}

/**
 * v1.2.23：searchUrl 相对地址按源地址补全（阅读式同款语义）。
 * `,{...}` 选项后缀先剥掉、只补 URL 部分、再拼回去；绝对地址原样返回。
 * 补不出来（如源地址本身非法）返回原模板，调用方再按 scheme 校验丢弃。
 */
fun resolveSearchUrlTemplate(template: String, baseUrl: String): String {
    val optStart = template.indexOf(",{")
    val urlPart = if (optStart < 0) template else template.substring(0, optStart)
    val suffix = if (optStart < 0) "" else template.substring(optStart)
    if (urlPart.startsWith("http://") || urlPart.startsWith("https://")) return template
    if (urlPart.isBlank() || baseUrl.isBlank()) return template
    val resolved = runCatching {
        java.net.URL(java.net.URL(baseUrl), urlPart).toString()
    }.getOrNull() ?: return template
    return resolved + suffix
}

/** v1.2.23：列表规则是不是 JSON 路径（`$.data.content[*]` 这类 API 源）。 */
fun isJsonListRule(rule: String): Boolean {
    val r = rule.trim()
    if (r.startsWith("$.")) return true
    if (!r.startsWith("<js>")) return false
    val end = r.indexOf("</js>")
    val rest = if (end < 0) "" else r.substring(end + 5).trim()
    return rest.startsWith("$.")
}

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
