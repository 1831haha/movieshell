package com.movieshell.data.novel

import com.movieshell.core.log.LogBus
import com.movieshell.core.js.LegadoJsEngine
import com.movieshell.core.net.DEFAULT_UA_CHROME120
import com.movieshell.core.net.HttpClient
import com.movieshell.core.net.PageResult
import com.movieshell.core.net.SearchRequest
import com.movieshell.core.net.SourceThrottle
import com.movieshell.core.net.decodeBytes
import com.movieshell.core.net.parseUrlOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

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
 *
 * v1.1.6 分层第 2 批：PageResult / SearchRequest / buildSearchRequest /
 * parseUrlOptions / decodeBytes 已移入 core/net（CSS 引擎与 JS 引擎共用），
 * 本文件只留解析与提取；客户端改走 core/net HttpClient（与原来 CmsApi
 * 转发的同一实例，行为不变）。
 */
object BookRuleEngine {

    private const val TAG = "BookRuleEngine"

    // v0.112.0 B1：小说 HTML 抓取（跟随重定向 + 幂等 GET 重试均在共享单例里已有），
    // 改用进程级共享单例。UA/Accept-Language 每次请求显式带，单例拦截器不覆盖。
    private val client = HttpClient.defaultClient()

    /** v1.1.6 反爬吸收项：单次抓取的源侧选项（默认全空 = 老行为）。 */
    data class FetchOptions(
        /** 书源自定义请求头（后于固定头应用，可覆盖 UA/Referer）。 */
        val headers: Map<String, String> = emptyMap(),
        /** 单请求超时（毫秒）；0 = 走共享客户端默认 15 秒。 */
        val timeoutMs: Int = 0,
    )

    /** 源配了超时才派生客户端（newBuilder 共享连接池）；不配时直接用共享单例。 */
    private fun clientFor(timeoutMs: Int): OkHttpClient {
        if (timeoutMs <= 0) return client
        val ms = timeoutMs.toLong()
        return client.newBuilder()
            .connectTimeout(ms, TimeUnit.MILLISECONDS)
            .readTimeout(ms, TimeUnit.MILLISECONDS)
            .writeTimeout(ms, TimeUnit.MILLISECONDS)
            .callTimeout(maxOf(60_000L, ms * 3), TimeUnit.MILLISECONDS)
            .build()
    }

    /** v1.1.6：浏览器味请求头（Accept + Sec-Fetch 组；Sec-Fetch-Site 取 cross-site——
     * App 没有发起方 origin，写 same-origin 是假话，跨站导航的诚实值就是 cross-site）。 */
    private fun Request.Builder.applyBrowserHeaders(): Request.Builder = apply {
        header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        header("Sec-Fetch-Dest", "document")
        header("Sec-Fetch-Mode", "navigate")
        header("Sec-Fetch-Site", "cross-site")
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

    suspend fun getHtml(url: String, ua: String? = null, options: FetchOptions = FetchOptions()): String? =
        fetchPage(url, ua, options = options)?.body

    /** v0.208.0：抓取结果 + HTTP 状态（null=网络异常等未拿到响应）。
     * v0.250.0：补 error——正文失败分类要区分超时/DNS/其他 IO，不能只看空页。 */
    data class FetchOutcome(val page: PageResult?, val httpCode: Int?, val error: Throwable? = null)

    /** v0.199.0：GET 抓取，返回正文与重定向后的落地 URL。 */
    suspend fun fetchPage(
        url: String,
        ua: String? = null,
        referer: String? = null,
        options: FetchOptions = FetchOptions(),
    ): PageResult? =
        fetchPageOutcome(url, ua, referer, options).page

    /**
     * v0.208.0：GET 抓取 + HTTP 状态。
     * 403 时调用方可用它触发 WebView 验证通道（见 NovelSourceEngine.gatedFetch），
     * 普通失败仍按原逻辑返回空页，不影响老调用方。
     * v1.1.6：出网口挂限速/退避（SourceThrottle）与浏览器味请求头。
     */
    suspend fun fetchPageOutcome(
        url: String,
        ua: String? = null,
        referer: String? = null,
        options: FetchOptions = FetchOptions(),
    ): FetchOutcome = withContext(Dispatchers.IO) {
        val host = hostOf(url)
        SourceThrottle.throttle(host)
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua ?: DEFAULT_UA_CHROME120)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .applyBrowserHeaders()
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .apply { options.headers.forEach { (k, v) -> header(k, v) } }
                .build()
            clientFor(options.timeoutMs).newCall(req).execute().use { resp ->
                if (resp.code == 403 || resp.code == 429) SourceThrottle.penalize(host)
                else if (resp.isSuccessful) SourceThrottle.clearPenalty(host)
                if (!resp.isSuccessful) return@use FetchOutcome(null, resp.code)
                val bytes = resp.body?.bytes() ?: return@use FetchOutcome(null, resp.code)
                FetchOutcome(PageResult(decodeBytes(bytes), resp.request.url.toString()), resp.code)
            }
        }.onFailure { LogBus.w(TAG, "抓取失败 $url ${it.javaClass.simpleName}: ${it.message}") }
            .getOrElse { FetchOutcome(null, null, it) }
    }

    /** v0.197.0：按 SearchRequest 抓搜索页；POST 发表单，body 按 charset 编字节。 */
    suspend fun fetchSearchHtml(request: SearchRequest, options: FetchOptions = FetchOptions()): String? =
        fetchSearchPage(request, options = options)?.body

    /** v0.199.0：按 SearchRequest 抓搜索页，返回正文与落地 URL。 */
    suspend fun fetchSearchPage(
        request: SearchRequest,
        ua: String? = null,
        options: FetchOptions = FetchOptions(),
    ): PageResult? =
        fetchSearchPageOutcome(request, ua, options).page

    /** 288：带选项后缀的通用抓取——POST 走表单体，GET/无后缀走原 fetchPageOutcome。 */
    suspend fun fetchPageWithOptions(
        urlWithOptions: String,
        ua: String? = null,
        referer: String? = null,
        options: FetchOptions = FetchOptions(),
    ): PageResult? =
        fetchPageWithOptionsOutcome(urlWithOptions, ua, referer, options).page

    suspend fun fetchPageWithOptionsOutcome(
        urlWithOptions: String,
        ua: String? = null,
        referer: String? = null,
        options: FetchOptions = FetchOptions(),
    ): FetchOutcome {
        val req = parseUrlOptions(urlWithOptions)
        if (req.method == "POST" && req.body != null) return fetchSearchPageOutcome(req, ua, options)
        return fetchPageOutcome(req.url, ua, referer, options)
    }

    /**
     * v0.208.0：按 SearchRequest 抓搜索页 + HTTP 状态（见 fetchPageOutcome）。
     * v1.1.6：同 fetchPageOutcome，挂限速/退避与浏览器味请求头；
     * 规则内联头（`,{...}` 里的 headers）最后应用，单条 URL 的覆盖优先于源级 header。
     */
    suspend fun fetchSearchPageOutcome(
        request: SearchRequest,
        ua: String? = null,
        options: FetchOptions = FetchOptions(),
    ): FetchOutcome = withContext(Dispatchers.IO) {
        val host = hostOf(request.url)
        SourceThrottle.throttle(host)
        runCatching {
            val builder = Request.Builder()
                .url(request.url)
                .header("User-Agent", ua ?: DEFAULT_UA_CHROME120)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .applyBrowserHeaders()
            options.headers.forEach { (k, v) -> builder.header(k, v) }
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            if (request.method == "POST" && request.body != null) {
                val charset = runCatching { Charset.forName(request.charsetName) }
                    .getOrDefault(Charsets.UTF_8)
                val mediaType = "application/x-www-form-urlencoded".toMediaType()
                builder.post(request.body.toByteArray(charset).toRequestBody(mediaType))
            }
            clientFor(options.timeoutMs).newCall(builder.build()).execute().use { resp ->
                if (resp.code == 403 || resp.code == 429) SourceThrottle.penalize(host)
                else if (resp.isSuccessful) SourceThrottle.clearPenalty(host)
                if (!resp.isSuccessful) return@use FetchOutcome(null, resp.code)
                val bytes = resp.body?.bytes() ?: return@use FetchOutcome(null, resp.code)
                FetchOutcome(PageResult(decodeBytes(bytes), resp.request.url.toString()), resp.code)
            }
        }.onFailure { LogBus.w(TAG, "搜索抓取失败 ${request.url} ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() ?: FetchOutcome(null, null)
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
            val el: Element = if (selector.isEmpty()) root else {
                // v1.2.26：// 开头的 XPath 选段先转 CSS（Jsoup 选 XPath 直接抛错，整段判空）。
                val sel = selector.trim().let {
                    if (it.startsWith("/")) LegadoJsEngine.xpathToCss(it) ?: it else it
                }
                root.selectFirst(sel)
            } ?: return ""
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
        // v1.2.26：XPath 列表规则先转 CSS（见 xpathToCss）。
        val sel = css.trim().let { if (it.startsWith("/")) LegadoJsEngine.xpathToCss(it) ?: it else it }
        return runCatching { Jsoup.parse(html).select(sel).toList() }.getOrDefault(emptyList())
    }

    fun parseDoc(html: String, baseUrl: String): org.jsoup.nodes.Document =
        Jsoup.parse(html, baseUrl)

    /**
     * v1.2.25：目录兜底——在详情页/目录页上找“目录”链接（如“查看章节目录”），
     * 跟过去再解析一次。只认链接文本含“目录”、地址与当前页不同（去 # 片段后比）；
     * 不写站名、不认站方结构，任何站的详情页挂目录链接都适用。
     * 黑岩实证：详情页改版后目录不在详情页、规则 tocUrl 的“开始阅读”也没了，
     * 详情页上的“查看章节目录”链到 w2.heiyan.com/chapter/{id} 才是真目录。
     */
    fun findTocLink(html: String, pageUrl: String): String? {
        val doc = parseDoc(html, pageUrl)
        val base = pageUrl.substringBefore("#")
        return doc.select("a:contains(目录)").firstNotNullOfOrNull { el ->
            val href = el.absUrl("href").substringBefore("#")
            if (href.isBlank() || href == base) null else href
        }
    }

    /**
     * v1.2.28：通用目录兜底——章节链接是否属于本书。
     * 书页 URL 去扩展名得目录（如 /book/2105794.html → /book/2105794/，
     * /book/143131 → /book/143131/）；章节 href 的路径以该目录开头才算本书章节。
     * 话本实证：裸 li>a 的 70 条全是导航/推荐书/页脚，真章节在 div.chapter-row 里，
     * href 都是 /book/<书号>/<章>.html。只认路径不认域名（w2 子站换 host 仍算同书）。
     */
    fun isSameBookChapterLink(href: String, bookUrl: String): Boolean {
        if (href.isBlank() || bookUrl.isBlank()) return false
        val bookPath = runCatching { java.net.URI(bookUrl).path }.getOrNull().orEmpty()
        if (bookPath.isBlank()) return false
        val dir = bookPath.removeSuffix(".html").removeSuffix(".htm").trimEnd('/') + "/"
        if (dir == "/") return false
        val hrefPath = runCatching { java.net.URI(href).path }.getOrNull().orEmpty()
        if (hrefPath.isBlank()) return false
        return hrefPath.startsWith(dir) && hrefPath.length > dir.length
    }

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
            // v1.2.26：XPath 正文规则先转 CSS（见 xpathToCss）。
            val sel = rule.trim().let { if (it.startsWith("/")) LegadoJsEngine.xpathToCss(it) ?: it else it }
            val el = doc.selectFirst(sel) ?: return ""
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
