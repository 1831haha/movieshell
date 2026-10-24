package com.movieshell.data.novel

import android.util.Log
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA_CHROME120
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * v0.61.0 书源规则引擎：OkHttp 抓取 + Jsoup CSS 解析。
 *
 * 规则语法（实用子集）：
 * - 列表：CSS 选择器，如 "div.book-item"
 * - 字段："选择器@属性"，如 "a.title@text"、"a@href"、"img@src"；
 *   选择器省略时作用于当前元素，如 "@text"、"@href"
 * - searchUrl 中的 {{key}} 替换为 URL 编码后的关键词
 * - 相对链接自动按页面 URL 补全为绝对地址
 * - v0.197.0：searchUrl 可带阅读式选项后缀
 *   `URL,{"method":"POST","body":"...{{key}}...","charset":"GBK","headers":{...}}`；
 *   没有后缀的老源走原 GET 逻辑，不受影响。
 */
object BookRuleEngine {

    private const val TAG = "BookRuleEngine"

    // v0.112.0 B1：小说 HTML 抓取（跟随重定向 + 幂等 GET 重试均在共享单例里已有），
    // 改用进程级共享单例。UA/Accept-Language 每次请求显式带，单例拦截器不覆盖。
    private val client = CmsApi.defaultClient()


    suspend fun getHtml(url: String, ua: String? = null): String? =
        fetchPage(url, ua)?.body

    /** v0.199.0：抓取结果（正文 + 落地 URL，供登录校验判断重定向）。 */
    data class PageResult(val body: String, val url: String)

    /** v0.208.0：抓取结果 + HTTP 状态（null=网络异常等未拿到响应）。 */
    data class FetchOutcome(val page: PageResult?, val httpCode: Int?)

    /** v0.199.0：GET 抓取，返回正文与重定向后的落地 URL。 */
    suspend fun fetchPage(url: String, ua: String? = null): PageResult? =
        fetchPageOutcome(url, ua).page

    /**
     * v0.208.0：GET 抓取 + HTTP 状态。
     * 403 时调用方可用它触发 WebView 验证通道（见 NovelSourceEngine.gatedFetch），
     * 普通失败仍按原逻辑返回空页，不影响老调用方。
     */
    suspend fun fetchPageOutcome(url: String, ua: String? = null): FetchOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua ?: DEFAULT_UA_CHROME120)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use FetchOutcome(null, resp.code)
                val bytes = resp.body?.bytes() ?: return@use FetchOutcome(null, resp.code)
                FetchOutcome(PageResult(decodeBytes(bytes), resp.request.url.toString()), resp.code)
            }
        }.onFailure { Log.w(TAG, "抓取失败 $url", it) }.getOrNull() ?: FetchOutcome(null, null)
    }

    /** v0.197.0：搜索请求（URL + 方法 + 表单体 + 编码 + 附加头）。 */
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

    /** v0.197.0：按 SearchRequest 抓搜索页；POST 发表单，body 按 charset 编字节。 */
    suspend fun fetchSearchHtml(request: SearchRequest): String? =
        fetchSearchPage(request)?.body

    /** v0.199.0：按 SearchRequest 抓搜索页，返回正文与落地 URL。 */
    suspend fun fetchSearchPage(request: SearchRequest, ua: String? = null): PageResult? =
        fetchSearchPageOutcome(request, ua).page

    /** v0.208.0：按 SearchRequest 抓搜索页 + HTTP 状态（见 fetchPageOutcome）。 */
    suspend fun fetchSearchPageOutcome(request: SearchRequest, ua: String? = null): FetchOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(request.url)
                .header("User-Agent", ua ?: DEFAULT_UA_CHROME120)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            if (request.method == "POST" && request.body != null) {
                val charset = runCatching { Charset.forName(request.charsetName) }
                    .getOrDefault(Charsets.UTF_8)
                val mediaType = "application/x-www-form-urlencoded".toMediaType()
                builder.post(request.body.toByteArray(charset).toRequestBody(mediaType))
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use FetchOutcome(null, resp.code)
                val bytes = resp.body?.bytes() ?: return@use FetchOutcome(null, resp.code)
                FetchOutcome(PageResult(decodeBytes(bytes), resp.request.url.toString()), resp.code)
            }
        }.onFailure { Log.w(TAG, "搜索抓取失败 ${request.url}", it) }.getOrNull() ?: FetchOutcome(null, null)
    }

    /** 页面解码：先 UTF-8，出现替换符则转 GBK（中文小说站常见）。 */
    internal fun decodeBytes(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('�')) bytes.toString(Charset.forName("GBK")) else utf8
    }

    /** 在 root 下按规则提字段。返回空字符串表示没提出来。 */
    fun extract(root: Element, pageUrl: String, rule: String): String {
        val r = rule.trim()
        if (r.isEmpty()) return ""
        return runCatching {
            val at = r.lastIndexOf('@')
            val (selector, attr) = if (at >= 0) {
                r.substring(0, at).trim() to r.substring(at + 1).trim()
            } else {
                r to "text"
            }
            val el: Element = if (selector.isEmpty()) root else root.selectFirst(selector) ?: return ""
            when (attr.lowercase()) {
                "text" -> el.text().trim()
                "html" -> el.html()
                "href", "src" -> absolutize(pageUrl, el.attr(attr).trim())
                else -> el.attr(attr).trim()
            }
        }.getOrDefault("")
    }

    fun selectList(html: String, css: String): List<Element> {
        if (css.isBlank()) return emptyList()
        return runCatching { Jsoup.parse(html).select(css).toList() }.getOrDefault(emptyList())
    }

    fun parseDoc(html: String, baseUrl: String): org.jsoup.nodes.Document =
        Jsoup.parse(html, baseUrl)

    /**
     * 正文提取：去掉 script/style；<br> 与块级元素都转成换行。
     *
     * v0.91.0 修复：此前用 el.textNodes() 只取直接文本节点；全本小说网等站正文
     * 由 <p> 包裹时直接文本节点只有空白，过滤后返回空串 → 阅读器只显示标题。
     * 现把块级元素尾部都追加换行再用 wholeText() 取全文，兼容
     * "直接文本 + <br>" 与 "<p> 包裹" 两种排版。
     */
    fun extractContent(doc: org.jsoup.nodes.Document, rule: String): String {
        if (rule.isBlank()) return ""
        return runCatching {
            val el = doc.selectFirst(rule) ?: return ""
            el.select("script, style").remove()
            // after 只追加换行文本节点，不复制正文；嵌套块产生的多余空行会被 filter 去掉
            el.select("br").forEach { it.after("\n") }
            el.select("p, div, h1, h2, h3, h4, h5, h6, li, blockquote, tr, section, article")
                .forEach { it.after("\n") }
            el.wholeText()
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n") { "　　$it" }
        }.getOrDefault("")
    }

    /** v0.198.0：阅读式正文替换。
     * 规则格式：每行一条，`正则##替换`；没有 ## 的行按删除处理；某条正则
     * 写坏了只跳过该条，不炸掉整章。替换后把 3 个以上连续换行压回两个，
     * 避免被删的广告行留出大片空白。
     */
    fun applyReplaceRegex(text: String, replaceRegex: String): String {
        if (text.isBlank() || replaceRegex.isBlank()) return text
        var result = text
        replaceRegex.split("\n").forEach { line ->
            if (line.isBlank()) return@forEach
            val sep = line.indexOf("##")
            val pattern = if (sep >= 0) line.substring(0, sep) else line
            val replacement = if (sep >= 0) line.substring(sep + 2) else ""
            if (pattern.isNotBlank()) {
                result = runCatching {
                    result.replace(Regex(pattern), replacement)
                }.getOrDefault(result)
            }
        }
        return result.replace(Regex("\n{3,}"), "\n\n")
    }
    private fun absolutize(pageUrl: String, href: String): String {
        if (href.isBlank()) return ""
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return runCatching {
            java.net.URL(java.net.URL(pageUrl), href).toString()
        }.getOrDefault(href)
    }
}
