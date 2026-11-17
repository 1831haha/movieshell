package com.movieshell.core.net

import org.json.JSONObject
import java.net.URLEncoder

/**
 * v1.1.6 分层第 2 批：搜索请求（URL + 方法 + 表单体 + 编码 + 附加头）。
 * 原 BookRuleEngine.SearchRequest / buildSearchRequest / parseUrlOptions，
 * 一字未改，只换包——CSS 搜索链与 JS 引擎的 fetchRaw 共用，放 core/net。
 * v1.2.24：补阅读 UrlOption 的 retry（失败重发次数）与 js（解析完再算一次 URL）。
 */
data class SearchRequest(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val charsetName: String = "UTF-8",
    val headers: Map<String, String> = emptyMap(),
    val retry: Int = 0,
    val jsOption: String? = null,
)

/**
 * v1.2.23：searchUrl 是否需要 JS 求值。
 * - `<js>` / `@js:`：可出现在任意位置（阅读 analyzeJs 段式求值，前面的字面量
 *   当 result 传进 JS），不只限前缀（如 Lofter/123读书的 searchUrl）。
 * - `{{...}}` 表达式块：除 `{{key}}` / `{{page}}`（buildSearchRequest 直接替换）
 *   之外的块都要跑 JS（如读零零小说的 `{{url=source.getKey(); ...}}`）。
 * 纯 URL 或只含 `{{key}}`/`{{page}}` 的模板返回 false。
 */
fun searchUrlNeedsJs(template: String): Boolean {
    val t = template.trim()
    if (t.contains("<js>") || t.contains("@js:")) return true
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
 * v1.2.24：JS 求值结果转模板文本（阅读 AnalyzeUrlCore.replaceKeyPageJs 同款）。
 * 数字表达式（如 {{page+1}}）Rhino 返回 Double，整数值去小数点——否则 URL
 * 里出现 page=2.0；字符串原样；其余 toString。
 */
fun formatJsTemplateValue(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
    else -> v.toString()
}

/**
 * v1.2.24：URL 里的 {{key}}/{{page}} 按段编码（阅读 encodeUrlParams 同款）。
 * 路径段（? 之前）恒 UTF-8；查询段（? 之后）按源 charset 编码——GBK 站的
 * 查询关键词必须发 GBK 字节，之前全按 UTF-8 发是错的。
 */
private fun replaceKeyPage(url: String, utf8Key: String, charsetKey: String, page: String): String {
    val q = url.indexOf('?')
    if (q < 0) return url.replace("{{key}}", utf8Key).replace("{{page}}", page)
    val path = url.substring(0, q).replace("{{key}}", utf8Key).replace("{{page}}", page)
    val query = url.substring(q).replace("{{key}}", charsetKey).replace("{{page}}", page)
    return path + query
}

/**
 * v0.197.0：解析 searchUrl。
 * - 无 `,{...}` 后缀：老行为，GET，{{key}} 按 UTF-8 编码。
 * - 有后缀：按 method/body/charset/headers/retry/js 解析；body 与查询段里的
 *   {{key}} 按 charset 编码（如 GBK 站），路径段仍按 UTF-8 编码；
 *   {{page}} 按传入页码替换（v1.2.24 分页）；js 选项由调用方求值后替换 URL。
 * - 后缀 JSON 解析失败时回落老行为，不炸掉老源。
 */
fun buildSearchRequest(template: String, keyword: String, page: Int = 1): SearchRequest {
    val key = keyword.trim()
    val pageStr = page.coerceAtLeast(1).toString()
    val utf8Key = URLEncoder.encode(key, "UTF-8")
    val optStart = template.indexOf(",{")
    if (optStart < 0) {
        return SearchRequest(
            url = replaceKeyPage(template, utf8Key, utf8Key, pageStr),
        )
    }
    val opts = runCatching { JSONObject(template.substring(optStart + 1)) }.getOrNull()
        ?: return SearchRequest(
            // 后缀不是合法 JSON：只取逗号前的纯 URL，不把垃圾后缀带进请求
            url = replaceKeyPage(template.substring(0, optStart), utf8Key, utf8Key, pageStr),
        )
    val charsetName = opts.optString("charset", "UTF-8").ifBlank { "UTF-8" }
    val encodedKey = runCatching { URLEncoder.encode(key, charsetName) }.getOrDefault(utf8Key)
    val headers = mutableMapOf<String, String>()
    opts.optJSONObject("headers")?.let { h ->
        h.keys().forEach { k -> headers[k] = h.optString(k) }
    }
    val body = opts.optString("body", "")
        .replace("{{key}}", encodedKey)
        .replace("{{page}}", pageStr)
        .ifBlank { null }
    return SearchRequest(
        url = replaceKeyPage(template.substring(0, optStart), utf8Key, encodedKey, pageStr),
        method = opts.optString("method", "GET").ifBlank { "GET" }.uppercase(),
        body = body,
        charsetName = charsetName,
        headers = headers,
        retry = opts.optInt("retry", 0),
        jsOption = opts.optString("js", "").ifBlank { null },
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
