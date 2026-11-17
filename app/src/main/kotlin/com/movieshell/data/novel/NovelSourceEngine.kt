package com.movieshell.data.novel

import android.content.Context
import com.movieshell.core.js.LegadoJsEngine
import com.movieshell.core.log.LogBus
import com.movieshell.core.net.HttpClient
import com.movieshell.core.net.PageResult
import com.movieshell.core.net.SourceThrottle
import com.movieshell.core.net.buildSearchRequest
import com.movieshell.core.net.formatJsTemplateValue
import com.movieshell.core.net.isJsonListRule
import com.movieshell.core.net.resolveSearchUrlTemplate
import com.movieshell.core.net.searchUrlNeedsJs
import com.movieshell.data.store.NovelStore
import com.movieshell.domain.model.BookDetailInfo
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.ContentFail
import com.movieshell.domain.model.ContentResult
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.domain.model.ParagraphComment
import com.movieshell.domain.source.NovelEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * v0.61.0 书源执行引擎：把 BookSource 规则跑通
 * 搜索 → 书籍详情 → 目录 → 章节正文。
 *
 * 所有网络 IO 走 BookRuleEngine；失败吞掉打日志，返回空。
 */
object NovelSourceEngine {

    private const val TAG = "NovelSourceEngine"

    /** v0.101.0：书源并发搜上限，书源齐发会被反爬封 IP（影视模块有自己的限流，互不干扰）。
     * v1.2.23：4 → 10——同域仍有 SourceThrottle 间隔限速，提并发只为让 2000+ 源的
     * 聚合搜索不再排 500 轮长队；单源 30 秒与整轮 120 秒封顶兜底慢源。 */
    private const val SEARCH_MAX_CONCURRENCY = 10
    private val searchSemaphore = Semaphore(SEARCH_MAX_CONCURRENCY)

    /** v1.2.23：聚合搜索里单个书源的预算（毫秒）——超了直接丢弃该源结果，不拖整轮。 */
    private const val SEARCH_SOURCE_TIMEOUT_MS = 30_000L

    /** v1.2.23：聚合搜索整轮预算（毫秒）——到点即收尾，已出的结果保留。 */
    private const val SEARCH_GLOBAL_BUDGET_MS = 120_000L

    /** v1.2.23：死站 host 的跳过时长（毫秒）——DNS/SSL/超时连不上的 host 在此时长内不再发请求。 */
    private const val DEAD_HOST_TTL_MS = 10 * 60_000L

    /** v1.2.23：死站 host → 恢复时间戳（内存态，进程重启清零）。 */
    private val deadHosts = ConcurrentHashMap<String, Long>()

    /** v1.2.23：该 host 是否在死站跳过期内。 */
    private fun isHostDead(host: String): Boolean {
        if (host.isBlank()) return false
        val until = deadHosts[host] ?: return false
        if (System.currentTimeMillis() < until) return true
        deadHosts.remove(host)
        return false
    }

    /** v1.2.23：连通性错误（DNS/SSL/超时/建连失败）才记死站；403/429 这类站方限流不记。 */
    private fun isConnectivityError(e: Throwable?): Boolean =
        e is UnknownHostException || e is SocketTimeoutException ||
            e is InterruptedIOException || e is SSLException || e is ConnectException

    /** v1.2.23：按最后一次抓取结果记死站（调用方在搜失败后调一次）。 */
    private fun markDeadHostIfConnectivityError(host: String, last: BookRuleEngine.FetchOutcome?) {
        if (host.isBlank() || !isConnectivityError(last?.error)) return
        if (deadHosts.size > 1000) deadHosts.entries.removeIf { it.value < System.currentTimeMillis() }
        deadHosts[host] = System.currentTimeMillis() + DEAD_HOST_TTL_MS
        LogBus.w(TAG, "搜书死站跳过 $host（10 分钟内不再请求）")
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

    /**
     * v1.1.12：最近一次搜书失败的书源名（内存态，不落盘；影视侧 SiteRepository
     * 的 failedSearchLabel 同款口径）。语义是最近一次：每次搜书入口先清、
     * 本次失败再记；取消不记。空结果且无失败 = 源确实没数据；空结果且有
     * 失败 = 执行失败，UI 据此把“没找到”和“搜失败了”分开说。
     */
    private val failedSearchNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 搜书入口先清失败汇总（新一轮开始，旧汇总作废）。 */
    fun clearSearchFailures() {
        failedSearchNames.clear()
    }

    /** 最近一次搜书的失败汇总文案，null=本次无书源失败。 */
    fun failedSearchLabel(): String? =
        if (failedSearchNames.isEmpty()) null else "${failedSearchNames.size} 个书源失败"

    // v1.1.6：ContentFail / ContentResult 已移入 domain/model（NovelModels.kt），
    // 供 NovelEngine 契约与 UI 共用。

    /**
     * v0.250.0：按最后一次抓取结果给正文失败定性。
     * fetchCount 是实际发过的请求数：验证通过后又重抓一次才仍 403，说明验证解不了，
     * 定 FORBIDDEN；只抓过一次就 403，验证通道还没跑过，定 VERIFY。
     */
    private fun classifyContentFailure(last: BookRuleEngine.FetchOutcome?, fetchCount: Int): ContentFail {
        val err = last?.error
        if (err is SocketTimeoutException || err is InterruptedIOException) return ContentFail.TIMEOUT
        if (err is UnknownHostException) return ContentFail.DNS
        if (last?.httpCode == 403) return if (fetchCount >= 2) ContentFail.FORBIDDEN else ContentFail.VERIFY
        if (err is IOException) return ContentFail.NETWORK
        return ContentFail.UNKNOWN
    }

    /** v0.250.0：正文 Referer 取章地址的父路径（多为详情页）；章地址本身无路径时不带。 */
    private fun parentReferer(url: String): String? {
        val trimmed = url.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        if (idx <= 8) return null
        return trimmed.substring(0, idx + 1)
    }

    /** v0.199.0：该源是否用到 JS 能力（是则走 LegadoJsEngine，否则走老 CSS 路径，原样不动）。 */
    private fun isJsSource(source: BookSource): Boolean = LegadoJsEngine.isJsSource(source.toJsRuleSource())

    private fun uaOf(source: BookSource): String? = source.headerUa.ifBlank { null }

    /** v1.1.6 反爬吸收项：源级出网配置——手动 Cookie 写进共享 Jar（之后随 Jar 落盘），
     * concurrentRate 记到同域限速表。每个公开入口调一次。 */
    private fun applySourceNetConfig(source: BookSource) {
        if (source.cookies.isNotBlank()) {
            HttpClient.putCookies(source.url, source.cookies)
        }
        val host = runCatching { java.net.URI(source.url).host.orEmpty() }.getOrDefault("")
        SourceThrottle.setGap(host, SourceThrottle.parseConcurrentRate(source.concurrentRate))
    }

    /** v1.1.6：本书源的抓取选项（自定义头 + 单请求超时），供各链路的 fetch 调用。 */
    private fun fetchOptions(source: BookSource): BookRuleEngine.FetchOptions =
        BookRuleEngine.FetchOptions(headers = source.headerMap, timeoutMs = source.timeout)

    /**
     * v0.199.0：searchUrl 求值：<js> 前缀跑 JS，否则原样（{{key}}/{{page}} 由 buildSearchRequest 处理）。
     * v1.2.23：补三类之前直接丢给 OkHttp 炸掉的写法——`@js:` 前缀（Lofter/123读书/
     * 青羽阅读整段都是 JS）、`{{...}}` 表达式块（读零零小说的
     * `{{url=source.getKey(); cookie.removeCookie(url)}}`）、相对地址（旧时光的
     * `/modules/article/search.php`、无忧小说网的 `/search.html` 等，按源地址补全）。
     * v1.2.24：JS 段式求值（阅读 AnalyzeUrlCore.analyzeJs 同款）——`<js>...</js>`
     * 可在串中任意位置、`@js:` 吃到串尾；每段 JS 之前字面量当 result 传进去，
     * JS 结果成为新串、段后字面量拼在后面；`{{...}}` 数字结果去 `.0`。
     * 求不出有效 http(s) 地址返回 null，调用方直接跳过该源、不弹验证窗。
     */
    private fun evalSearchUrl(
        source: BookSource,
        ctx: LegadoJsEngine.JsCtx,
        state: LegadoJsEngine.JsState,
    ): String? {
        val raw = source.searchUrl.trim()
        if (raw.isBlank()) return null
        var template = raw
        // 1. JS 段式求值：<js>...</js> / @js:（段式，不只限前缀）
        template = evalJsSegments(template, ctx, state) ?: run {
            LogBus.w(TAG, "搜索地址 JS 求值失败 ${source.name}，跳过本次搜索")
            return null
        }
        // 2. {{...}} 表达式块：逐块跑 JS 求值替换（{{key}}/{{page}} 留给 buildSearchRequest）。
        // 按位置替换，不按内容拼串——块内带空格（如 {{ url=... }}）也能对上。
        var failed = false
        template = Regex("\\{\\{([^{}]+)\\}\\}").replace(template) { m ->
            val inner = m.groupValues[1].trim()
            if (inner == "key" || inner == "page") {
                m.value
            } else {
                val out = LegadoJsEngine.evalScript(
                    inner,
                    LegadoJsEngine.bindings(ctx, state, result = "", src = ""),
                    LegadoJsEngine.jsLibPrelude(ctx.source),
                )
                if (out == null) {
                    failed = true
                    ""
                } else {
                    // v1.2.24：数字结果去 .0（{{page+1}} 得 "2" 不是 "2.0"）
                    formatJsTemplateValue(out)
                }
            }
        }
        if (failed) {
            LogBus.w(TAG, "搜索地址模板求值失败 ${source.name}，跳过本次搜索")
            return null
        }
        // 3. 相对地址按源地址补全；补完仍不是 http(s) 直接判空
        template = resolveSearchUrlTemplate(template, source.url)
        if (!template.startsWith("http://") && !template.startsWith("https://")) {
            LogBus.w(TAG, "搜索地址无效 ${source.name}，跳过本次搜索")
            return null
        }
        return template
    }

    /**
     * v1.2.24：searchUrl 里的 JS 段求值（阅读 analyzeJs 段式语义）。
     * `<js>code</js>` 取 code，`@js:code` 取串尾 code；段前字面量当 result 传进
     * JS（JS 里用 result 拼前缀，如 `@js:result+encodeURIComponent(key)`）；
     * JS 输出成为新串，段后字面量拼在后面。任一段求值失败或 `<js>` 未闭合
     * 返回 null。无 JS 段时原样返回。
     */
    private fun evalJsSegments(
        template: String,
        ctx: LegadoJsEngine.JsCtx,
        state: LegadoJsEngine.JsState,
    ): String? {
        if (!template.contains("<js>") && !template.contains("@js:")) return template
        val out = StringBuilder()
        var pendingLiteral = StringBuilder()
        var i = 0
        fun evalOne(code: String, resultValue: String): String? {
            val r = LegadoJsEngine.evalScript(
                code,
                LegadoJsEngine.bindings(ctx, state, result = resultValue, src = resultValue),
                LegadoJsEngine.jsLibPrelude(ctx.source),
            ) ?: return null
            return LegadoJsEngine.toJsString(r)
        }
        while (i < template.length) {
            if (template.startsWith("<js>", i)) {
                val end = template.indexOf("</js>", i + 4)
                if (end < 0) return null
                val jsOut = evalOne(template.substring(i + 4, end), pendingLiteral.toString())
                    ?: return null
                out.append(jsOut)
                pendingLiteral = StringBuilder()
                i = end + 5
            } else if (template.startsWith("@js:", i)) {
                val jsOut = evalOne(template.substring(i + 4), pendingLiteral.toString())
                    ?: return null
                out.append(jsOut)
                pendingLiteral = StringBuilder()
                i = template.length
            } else {
                pendingLiteral.append(template[i])
                i++
            }
        }
        out.append(pendingLiteral)
        return out.toString()
    }

    /**
     * v1.2.23：单源搜索的请求构造（求值 + 补全 + 校验一次做完）。
     * 返回 null = 该源本次搜不了（地址求不出/无效），调用方记失败、不发请求、不弹验证。
     * ctx/state 由调用方传（JS 分支复用同一份，供后续解析与登录校验用）。
     * v1.2.24：page 透传（{{page}} 按页码替换，分出第 2 页起）；选项 `js`
     * 求值后替换最终 URL（阅读 UrlOption.js 同款）。
     */
    private fun buildSearchReq(
        source: BookSource,
        keyword: String,
        ctx: LegadoJsEngine.JsCtx,
        state: LegadoJsEngine.JsState,
        page: Int = 1,
    ): com.movieshell.core.net.SearchRequest? {
        val template = if (searchUrlNeedsJs(source.searchUrl)) {
            evalSearchUrl(source, ctx, state) ?: return null
        } else {
            val resolved = resolveSearchUrlTemplate(source.searchUrl.trim(), source.url)
            if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) {
                LogBus.w(TAG, "搜索地址无效 ${source.name}，跳过本次搜索")
                return null
            }
            resolved
        }
        val req = buildSearchRequest(template, keyword, page)
        val jsCode = req.jsOption ?: return req
        val out = LegadoJsEngine.evalScript(
            jsCode,
            LegadoJsEngine.bindings(ctx, state, result = req.url, src = req.url),
            LegadoJsEngine.jsLibPrelude(ctx.source),
        )
        val newUrl = out?.let { LegadoJsEngine.toJsString(it) }.orEmpty()
        if (newUrl.isBlank() || (!newUrl.startsWith("http://") && !newUrl.startsWith("https://"))) {
            LogBus.w(TAG, "搜索地址选项 js 求值失败 ${source.name}，跳过本次搜索")
            return null
        }
        return req.copy(url = newUrl)
    }

    /**
     * v1.2.24：首抓失败重发（阅读 UrlOption.retry 同款，`retry` 写在 searchUrl
     * 的 `,{...}` 后缀里，默认 0 即不重发）。只重发首抓，不碰验证流程；
     * 次数封顶 3 次，防源里写个大数把搜索拖死。
     */
    private suspend fun fetchWithRetry(
        times: Int,
        fetch: suspend () -> BookRuleEngine.FetchOutcome,
    ): BookRuleEngine.FetchOutcome {
        var last: BookRuleEngine.FetchOutcome? = null
        repeat(times.coerceIn(0, 3) + 1) {
            last = fetch()
            if (last?.page != null) return last!!
        }
        return last!!
    }

    /**
     * v1.2.23：JSON 搜索列表的结果映射（`$.data.content[*]` 这类 API 源）。
     * 条目是 JSON 对象，字段规则走 evalJsonField（`$.` 取值 / `@js:` 拼接 / `##` 去标签）。
     * v1.2.24：组内按 remoteId 去重（阅读 mergeGroup/distinctBy bookUrl 同款，
     * 劣质源翻页会重复返回上一页的书）。
     */
    private fun searchJsonItems(
        html: String,
        pageUrl: String,
        source: BookSource,
        ctx: LegadoJsEngine.JsCtx,
        state: LegadoJsEngine.JsState,
    ): List<NovelSearchResult> {
        val items = LegadoJsEngine.evalJsonListRule(html, source.ruleSearchList, ctx, state)
        return items.mapNotNull { item ->
            val name = LegadoJsEngine.evalJsonField(item, source.ruleSearchName.ifBlank { "@text" }, ctx, state)
            val bookUrl = LegadoJsEngine.evalJsonField(item, source.ruleSearchBookUrl.ifBlank { "a@href" }, ctx, state)
            if (name.isBlank() || bookUrl.isBlank()) return@mapNotNull null
            NovelSearchResult(
                sourceKey = source.key,
                remoteId = absUrl(pageUrl, bookUrl),
                title = name,
                author = LegadoJsEngine.evalJsonField(item, source.ruleSearchAuthor, ctx, state).ifBlank { null },
                cover = LegadoJsEngine.evalJsonField(item, source.ruleSearchCover, ctx, state).ifBlank { null },
            )
        }.distinctBy { it.remoteId }
    }

    /** 在单个书源里搜书。v0.197.0：搜索走 POST 感知的请求（老 GET 源行为不变）。v0.199.0：JS 源走 LegadoJsEngine。 */
    /**
     * v0.208.0：403 自动过验证（阅读同款路子）。
     * 直连被站方墙拦（空页时）弹全屏 WebView 走一次真实浏览器验证（浏览器指纹 +
     * Cookie 落袋），Cookie 回写共享 Jar 后把原请求重抓一次；用户取消/超时/验证后
     * 仍失败则返回 null，走原空结果逻辑。只重抓一次，不递归、不循环弹。
     * allowGate=false 时（如后台更新检查）不弹验证窗，原样返回空。
     */
    private suspend fun gatedFetch(
        source: BookSource,
        ua: String?,
        gateUrl: String,
        allowGate: Boolean,
        label: String = "搜书",
        fetch: suspend () -> BookRuleEngine.FetchOutcome,
    ): PageResult? {
        val first = fetch()
        if (first.page != null) return first.page
        // v0.209.0：用户主动发起时，空页即弹验证——墙的拦法不止 403（沙箱测得 403，
        // 手机 VPN 下常表现为连接被重置/超时，httpCode 不是 403），只认 403 会漏弹。
        // 后台（allowGate=false）仍不弹。只重抓一次，不递归。
        // v0.210.0：先后台验证（不弹页面，挑战自己过了直接重抓）；后台过不了
        // （真人验证码、超时）再弹可见验证页兜底。
        // v0.225.0：全链路诊断——首抓、后台验证、重抓三处结果全记进悬浮窗，
        // 只记状态码/长度/有无 cf_clearance，不记正文与 Cookie 值。
        if (!allowGate) return null
        LogBus.w(TAG, "${label}抓取 ${source.name} 首抓空 httpCode=${first.httpCode}，进验证")
        return verifyAndRefetch(source, ua, gateUrl, allowGate, label, fetch)
    }

    /**
     * v0.250.0：gatedFetch 的验证尾巴抽出——正文首抓 200 但疑似验证页时也要复用它，
     * 搜索/目录仍走 gatedFetch，触发语义不变。
     */
    private suspend fun verifyAndRefetch(
        source: BookSource,
        ua: String?,
        gateUrl: String,
        allowGate: Boolean,
        label: String = "搜书",
        fetch: suspend () -> BookRuleEngine.FetchOutcome,
    ): PageResult? {
        if (!allowGate) return null
        val bg = runCatching { VerifyGate.awaitVerifyBackground(gateUrl, ua) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        LogBus.d(TAG, "${label}验证 ${source.name} 后台=${if (bg == null) "失败" else "通过 cf=${bg.cookieHeader.contains("cf_clearance=")} htmlLen=${bg.html.length} cookieLen=${bg.cookieHeader.length}"}")
        val result = bg ?: runCatching { VerifyGate.awaitVerify(gateUrl, source.name, ua) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        if (result == null) {
            LogBus.w(TAG, "${label}验证 ${source.name} 可见验证未通过")
            return null
        }
        if (result.cookieHeader.isNotBlank()) HttpClient.putCookies(result.url, result.cookieHeader)
        val again = fetch()
        if (again.page == null) LogBus.w(TAG, "${label}抓取 ${source.name} 重抓空 httpCode=${again.httpCode}")
        return again.page
    }

    suspend fun search(
        source: BookSource,
        keyword: String,
        allowGate: Boolean = false,
        pageNum: Int = 1,
    ): List<NovelSearchResult> {
        applySourceNetConfig(source)
        if (source.searchUrl.isBlank() || source.ruleSearchList.isBlank()) return emptyList()
        if (!isJsSource(source)) {
            val state = LegadoJsEngine.JsState(source.key)
            val ctx = LegadoJsEngine.JsCtx(source = source.toJsRuleSource(), baseUrl = source.url, keyword = keyword, page = pageNum)
            val req = buildSearchReq(source, keyword, ctx, state, pageNum) ?: run {
                // v1.1.12：搜索地址算不出来是搜失败了，不是“没书”。
                failedSearchNames.add(source.name)
                return emptyList()
            }
            val searchHost = hostOf(req.url)
            if (isHostDead(searchHost)) {
                failedSearchNames.add(source.name)
                return emptyList()
            }
            var lastOutcome: BookRuleEngine.FetchOutcome? = null
            val html = gatedFetch(source, null, source.url, allowGate) {
                fetchWithRetry(req.retry) {
                    BookRuleEngine.fetchSearchPageOutcome(req, options = fetchOptions(source))
                        .also { lastOutcome = it }
                }
            }?.body ?: run {
                // v1.1.12：页没抓到是搜失败了，不是“没书”——记一笔，搜书页据此分开说。
                failedSearchNames.add(source.name)
                markDeadHostIfConnectivityError(searchHost, lastOutcome)
                return emptyList()
            }
            val url = req.url
            // v1.2.23：JSON 搜索列表（$.data.content[*] 这类 API 源）走 JSON 解析，不走 CSS
            if (isJsonListRule(source.ruleSearchList)) {
                return searchJsonItems(html, url, source, ctx, state)
            }
            return BookRuleEngine.selectList(html, source.ruleSearchList).mapNotNull { el ->
                val name = BookRuleEngine.extract(el, url, source.ruleSearchName.ifBlank { "@text" })
                val bookUrl = BookRuleEngine.extract(el, url, source.ruleSearchBookUrl.ifBlank { "a@href" })
                if (name.isBlank() || bookUrl.isBlank()) return@mapNotNull null
                NovelSearchResult(
                    sourceKey = source.key,
                    remoteId = bookUrl,
                    title = name,
                    author = BookRuleEngine.extract(el, url, source.ruleSearchAuthor).ifBlank { null },
                    cover = BookRuleEngine.extract(el, url, source.ruleSearchCover).ifBlank { null },
                )
            }.distinctBy { it.remoteId }
        }
        return withContext(Dispatchers.IO) {
            val ua = uaOf(source)
            val state = LegadoJsEngine.JsState(source.key)
            val ctx = LegadoJsEngine.JsCtx(source = source.toJsRuleSource(), baseUrl = source.url, keyword = keyword, page = pageNum)
            val req = buildSearchReq(source, keyword, ctx, state, pageNum) ?: run {
                // v1.1.12：搜索地址算不出来是搜失败了，不是“没书”。
                failedSearchNames.add(source.name)
                return@withContext emptyList()
            }
            val searchHost = hostOf(req.url)
            if (isHostDead(searchHost)) {
                failedSearchNames.add(source.name)
                return@withContext emptyList()
            }
            var lastOutcome: BookRuleEngine.FetchOutcome? = null
            var page = gatedFetch(source, ua, source.url, allowGate) {
                fetchWithRetry(req.retry) {
                    BookRuleEngine.fetchSearchPageOutcome(req, ua, options = fetchOptions(source))
                        .also { lastOutcome = it }
                }
            }
            if (page == null) {
                // v0.223.0：搜书诊断——未抓到页以前是静默空，记一行才能分清是请求问题还是解析问题。
                LogBus.w(TAG, "搜书诊断 ${source.name} 未抓到页")
                // v1.1.12：同上，记一笔失败。
                failedSearchNames.add(source.name)
                markDeadHostIfConnectivityError(searchHost, lastOutcome)
                return@withContext emptyList()
            }
            // v0.209.0：登录后重抓也过验证通道——999 这类源先握手再被墙时，
            // 原直接重抓拿不到页又不弹窗，搜索静默为空。
            val beforeLoginLen = page.body.length
            page = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), page, ctx, state, refetch = {
                val req2 = buildSearchReq(source, keyword, ctx, state, pageNum)
                if (req2 == null) {
                    null
                } else {
                    gatedFetch(source, ua, source.url, allowGate) {
                        fetchWithRetry(req2.retry) {
                            BookRuleEngine.fetchSearchPageOutcome(req2, ua, options = fetchOptions(source))
                                .also { lastOutcome = it }
                        }
                    }
                }
            })
            val pageCtx = ctx.copy(baseUrl = page.url)
            // v1.2.23：JSON 搜索列表（$.data.content[*] 这类 API 源）走 JSON 解析，不走 CSS
            if (isJsonListRule(source.ruleSearchList)) {
                val results = searchJsonItems(page.body, page.url, source, pageCtx, state)
                LogBus.d(TAG, "搜书诊断 ${source.name} 校验前长=$beforeLoginLen 校验后长=${page.body.length} JSON 解析出 ${results.size} 条")
                return@withContext results
            }
            val items = LegadoJsEngine.evalListRule(page.body, page.url, source.ruleSearchList, pageCtx, state)
            // v0.223.0：搜书诊断——只记长度与条数，不记正文；校验前后长度一变就说明 1234 握手改写了页。
            LogBus.d(TAG, "搜书诊断 ${source.name} 校验前长=$beforeLoginLen 校验后长=${page.body.length} 解析出 ${items.size} 条")
            items.mapNotNull { el ->
                val name = LegadoJsEngine.evalTextRule(el, page.url, source.ruleSearchName.ifBlank { "@text" }, pageCtx, state)
                val bookUrl = LegadoJsEngine.evalTextRule(el, page.url, source.ruleSearchBookUrl.ifBlank { "a@href" }, pageCtx, state)
                if (name.isBlank() || bookUrl.isBlank()) return@mapNotNull null
                NovelSearchResult(
                    sourceKey = source.key,
                    remoteId = absUrl(page.url, bookUrl),
                    title = name,
                    author = LegadoJsEngine.evalTextRule(el, page.url, source.ruleSearchAuthor, pageCtx, state).ifBlank { null },
                    cover = LegadoJsEngine.evalTextRule(el, page.url, source.ruleSearchCover, pageCtx, state).ifBlank { null },
                )
            }.distinctBy { it.remoteId }
        }
    }

    /**
     * v1.2.24：单源搜索结果（带精准过滤掉的条数，供界面提示“已过滤 N 条”）。
     */
    data class SourceSearchResult(
        val source: BookSource,
        val books: List<NovelSearchResult>,
        val filtered: Int = 0,
    )

    /**
     * v1.2.24：精准过滤（阅读 precisionSearch 同款：只留书名/作者含关键词的书）。
     * 返回（留下的书，被滤掉的条数）。默认关，开关在找书页、持久化在 VM。
     */
    fun applyPrecisionFilter(
        books: List<NovelSearchResult>,
        keyword: String,
        precision: Boolean,
    ): Pair<List<NovelSearchResult>, Int> {
        if (!precision) return books to 0
        val kept = books.filter { it.title.contains(keyword) || (it.author?.contains(keyword) == true) }
        return kept to (books.size - kept.size)
    }

    /**
     * 多源并发搜，结果按源分组返回。v0.101.0：Semaphore(4) 限流防反爬封 IP。
     * v1.2.23：聚合搜索永不弹验证窗——2000+ 源时每个失败源都弹一次全屏验证，
     * 搜索永远跑不完、用户被弹窗淹没（2026-11-17 用户报障实证）。验证只留给
     * 单源搜索（search(allowGate=true)），聚合入口一律强制关掉。
     * 批量版仍保留（换源弹窗等调用方过渡用），内部走流式版收集。
     */
    suspend fun searchAll(
        sources: List<BookSource>,
        keyword: String,
        allowGate: Boolean = false,
    ): List<Pair<BookSource, List<NovelSearchResult>>> {
        // 聚合搜索强制不弹验证（见上），allowGate 参数仅为兼容旧调用方保留
        return searchAllFlow(sources, keyword).map { it.source to it.books }.toList()
    }

    /**
     * v1.2.23：多源流式搜——每源出结果就发一个，不等 2000+ 源全跑完。
     * 找书页/换源弹窗改走它：首批结果 1~2 秒即现，不再白屏几分钟。
     * - 验证强制关（见 searchAll 注释）；- 单源 15 秒超时丢弃；
     * - 整轮 120 秒封顶，到点收尾、已出结果保留；- 取消原样抛出（v0.247.0 语义）。
     * v1.2.24：precision 开时按阅读口径滤掉书名/作者不含关键词的书，
     * 滤掉数随结果一起发（界面汇总提示）；全滤光的源也发一条（只带 filtered），
     * 免得过滤数丢了。
     */
    fun searchAllFlow(
        sources: List<BookSource>,
        keyword: String,
        precision: Boolean = false,
    ): Flow<SourceSearchResult> = channelFlow {
        withTimeoutOrNull(SEARCH_GLOBAL_BUDGET_MS) {
            val jobs = sources.map { s ->
                launch {
                    val result = withTimeoutOrNull(SEARCH_SOURCE_TIMEOUT_MS) {
                        searchSemaphore.withPermit {
                            runCatching { search(s, keyword, allowGate = false) }
                                .onFailure {
                                    // v0.247.0（取消语义）：取消不算源失败——原样抛出，
                                    // 让协程正常取消（v0.230.0 bookDetail 同款）；否则旧搜索
                                    // 被取消后仍正常返回空结果，覆盖新搜索的结果。
                                    if (it is CancellationException) throw it
                                    // v1.1.12：记一笔失败，搜书页据此区分“真没书”和“搜失败了”。
                                    failedSearchNames.add(s.name)
                                    LogBus.w(TAG, "搜索失败 ${s.name}: ${it.message}")
                                }
                                .getOrDefault(emptyList())
                        }
                    } ?: emptyList()
                    val (kept, dropped) = applyPrecisionFilter(result, keyword, precision)
                    if (kept.isNotEmpty() || dropped > 0) send(SourceSearchResult(s, kept, dropped))
                }
            }
            jobs.joinAll()
        }
    }

    /**
     * 拉目录。bookUrl 为空时尝试直接用 remoteId。
     *
     * v0.89.0：支持 ruleBookTocUrl（详情页→目录页两步跳转）——有些站详情页不内嵌
     * 目录，只有一个"章节列表"按钮（如全本小说网）；规则为空时保持旧行为。
     */
    suspend fun chapters(source: BookSource, book: NovelSearchResult): List<ChapterMeta> {
        applySourceNetConfig(source)
        val detailUrl = book.remoteId.ifBlank { return emptyList() }
        if (!isJsSource(source)) {
            var tocUrl = detailUrl
            if (source.ruleBookTocUrl.isNotBlank()) {
                // 288：详情页与目录页都走 fetchPageWithOptions——解析出的 tocUrl
                // 本身可能就是 POST 式 URL，旧 getHtml 只发 GET 会直接失败。
                // 落地 URL 用返回的 page.url（已剥掉选项后缀），相对链接才补得对。
                val detailPage = BookRuleEngine.fetchPageWithOptions(detailUrl, options = fetchOptions(source)) ?: return emptyList()
                val resolved = BookRuleEngine.extract(
                    BookRuleEngine.parseDoc(detailPage.body, detailPage.url),
                    detailPage.url,
                    source.ruleBookTocUrl,
                )
                if (resolved.isNotBlank()) tocUrl = resolved
            }
            // v1.2.25：目录兜底——解析出 0 章时，跟详情页/目录页上的“目录”链接再解析一次
            // （黑岩详情页改版：目录不在详情页，规则 tocUrl 的“开始阅读”也没了；通用结构，不写站名）。
            // 第二项 = 是否走了通用兜底（源规则在该页选空）；取大逻辑只在兜底时触发。
            fun parseTocPage(body: String, pageUrl: String): Pair<List<ChapterMeta>, Boolean> {
                val doc = BookRuleEngine.parseDoc(body, pageUrl)
                val listRule = source.ruleTocList.ifBlank { return emptyList<ChapterMeta>() to false }
                // v1.2.26：XPath 列表规则先转 CSS；源规则选空时，用通用兜底再试一次。
                val cssRule = listRule.trim().let {
                    if (it.startsWith("/")) LegadoJsEngine.xpathToCss(it) ?: it else it
                }
                var items = runCatching { doc.select(cssRule).toList() }.getOrDefault(emptyList())
                var usedFallback = false
                if (items.isEmpty()) {
                    // v1.2.28：通用兜底不再裸取 li>a（话本实证：70 条全是导航/推荐书/页脚，
                    // 真章节在 div.chapter-row 里）；改取全页 a[href] 再按书页目录过滤，
                    // ≥3 条才用，否则回退裸 li>a（防章节 URL 不含书号的站）。
                    usedFallback = true
                    val all = runCatching { doc.select("a[href]").toList() }.getOrDefault(emptyList())
                    val kept = all.filter { el ->
                        BookRuleEngine.isSameBookChapterLink(el.absUrl("href"), detailUrl)
                    }
                    items = if (kept.size >= 3) kept
                    else runCatching { doc.select("li > a").toList() }.getOrDefault(emptyList())
                    if (items.isNotEmpty()) LogBus.d(TAG, "目录通用兜底 ${source.name} 用同书链接解析出 ${items.size} 章")
                }
                return (items.mapIndexedNotNull { i, el ->
                    val title = BookRuleEngine.extract(el, pageUrl, source.ruleTocTitle.ifBlank { "@text" })
                    val url = BookRuleEngine.extract(el, pageUrl, source.ruleTocUrl.ifBlank { "@href" })
                    if (title.isBlank() || url.isBlank()) return@mapIndexedNotNull null
                    // v1.1.3：段评最小版——提评论页地址（规则空时 extract 返回空串，不参与过滤）。
                    val commentUrl = BookRuleEngine.extract(el, pageUrl, source.ruleTocCommentUrl)
                    ChapterMeta(index = i, title = title, url = url, commentUrl = commentUrl)
                }
                    // v0.194.0：导航章兜底过滤（见 NovelStore.isNavChapterTitle）。
                    .filterNot { NovelStore.isNavChapterTitle(it.title) }
                    .mapIndexed { i, m -> m.copy(index = i) }) to usedFallback
            }
            val tocPage = BookRuleEngine.fetchPageWithOptions(tocUrl, options = fetchOptions(source)) ?: return emptyList()
            // 目录页可能是详情页（规则里 toc 列表直接在详情页上）
            var (result, usedFallback) = parseTocPage(tocPage.body, tocPage.url)
            var triedUrl: String? = null
            if (result.isEmpty()) {
                val fallbackUrl = BookRuleEngine.findTocLink(tocPage.body, tocPage.url)
                if (fallbackUrl != null) {
                    triedUrl = fallbackUrl
                    val fallbackPage = BookRuleEngine.fetchPageWithOptions(fallbackUrl, options = fetchOptions(source))
                    if (fallbackPage != null) {
                        val (r2, f2) = parseTocPage(fallbackPage.body, fallbackPage.url)
                        result = r2
                        usedFallback = f2
                        LogBus.d(TAG, "目录兜底 ${source.name} 跟进 $fallbackUrl 解析出 ${result.size} 章")
                    }
                }
            }
            // v1.2.28：兜底出的目录可能不全（如话本详情页只 inline 43/57 章）；
            // 页上若有“目录”链接指向别的目录页，跟过去再解析一次，取章数多的那份。
            if (usedFallback && result.isNotEmpty()) {
                val moreUrl = BookRuleEngine.findTocLink(tocPage.body, tocPage.url)
                if (moreUrl != null && moreUrl != tocUrl && moreUrl != triedUrl) {
                    val morePage = BookRuleEngine.fetchPageWithOptions(moreUrl, options = fetchOptions(source))
                    if (morePage != null) {
                        val (more, _) = parseTocPage(morePage.body, morePage.url)
                        if (more.size > result.size) {
                            LogBus.d(TAG, "目录兜底 ${source.name} 目录页 ${more.size} 章多于当前 ${result.size} 章，用目录页")
                            result = more
                        }
                    }
                }
            }
            return result
        }
        // v0.199.0：JS 源目录（含 nextTocUrl 分页：单 URL 循环跟随，整批 URL 逐页抓完）。
        return withContext(Dispatchers.IO) {
            if (source.ruleTocList.isBlank()) return@withContext emptyList()
            val ua = uaOf(source)
            val state = LegadoJsEngine.JsState(source.key)
            val ctx = LegadoJsEngine.JsCtx(
                source = source.toJsRuleSource(),
                baseUrl = detailUrl,
                bookName = book.title,
                bookUrl = book.remoteId,
                bookAuthor = book.author.orEmpty(),
            )
            var tocUrl = detailUrl
            if (source.ruleBookTocUrl.isNotBlank()) {
                val detailPage = BookRuleEngine.fetchPageWithOptions(detailUrl, ua, options = fetchOptions(source)) ?: return@withContext emptyList()
                val checked = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), detailPage, ctx, state) {
                    // v0.227.0：握手后必须重抓——999 的 1234 握手把页换成
                    // “success”短串，refetch 为 null 时这页被当成详情页解析，
                    // 目录永远为空（搜书 136 行有 refetch 才正常，目录四处漏了）。
                    BookRuleEngine.fetchPageWithOptions(detailUrl, ua, options = fetchOptions(source))
                }
                val doc = BookRuleEngine.parseDoc(checked.body, checked.url)
                val resolved = LegadoJsEngine.evalTextRule(doc, checked.url, source.ruleBookTocUrl, ctx, state)
                if (resolved.isNotBlank()) tocUrl = resolved
            }
            // 288：首抓、握手重抓、分页抓一律走 fetchPageWithOptions，
            // tocUrl / nextTocUrl 带 POST 后缀时才发得出表单；无后缀时与原 GET 等价。
            // v0.230.0：首抓失败不直接判空——站慢/抖动时给一次重试机会，仍失败记一行诊断（上次日志缺这行，分不清是没抓到还是规则没命中）。
            val firstPage = BookRuleEngine.fetchPageWithOptions(tocUrl, ua, options = fetchOptions(source))
                ?: BookRuleEngine.fetchPageWithOptions(tocUrl, ua, options = fetchOptions(source))
                ?: run { LogBus.d(TAG, "目录首抓失败 ${source.name} $tocUrl"); return@withContext emptyList() }
            val firstLen = firstPage.body.length
            var curPage = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), firstPage, ctx, state) {
                BookRuleEngine.fetchPageWithOptions(tocUrl, ua, options = fetchOptions(source))
            }
            val checkedLen = curPage.body.length
            val all = mutableListOf<ChapterMeta>()
            fun collect(html: String, pageUrl: String, listRule: String = source.ruleTocList) {
                val pageCtx = ctx.copy(baseUrl = pageUrl)
                LegadoJsEngine.evalListRule(html, pageUrl, listRule, pageCtx, state).forEach { el ->
                    val title = LegadoJsEngine.evalTextRule(el, pageUrl, source.ruleTocTitle.ifBlank { "@text" }, pageCtx, state)
                    val url = LegadoJsEngine.evalTextRule(el, pageUrl, source.ruleTocUrl.ifBlank { "@href" }, pageCtx, state)
                    if (title.isNotBlank() && url.isNotBlank()) {
                        // v1.1.3：段评最小版——提评论页地址（规则空时 evalTextRule 返回空串）。
                        val commentUrl = LegadoJsEngine.evalTextRule(el, pageUrl, source.ruleTocCommentUrl, pageCtx, state)
                        all += ChapterMeta(index = all.size, title = title, url = url, commentUrl = commentUrl)
                    }
                }
            }
            // v1.2.25：分页抓取抽成 harvest——首轮 0 章时兜底跟进“目录”链接再 harvest 一次，共用同一套分页逻辑。
            suspend fun harvest(start: PageResult, listRule: String = source.ruleTocList) {
                var page = start
                var g = 0
                while (g++ < 50) {
                    collect(page.body, page.url, listRule)
                    if (source.ruleTocNext.isBlank()) break
                    val pageCtx = ctx.copy(baseUrl = page.url)
                    val nexts = LegadoJsEngine.evalNextUrls(page.body, page.url, source.ruleTocNext, pageCtx, state)
                    if (nexts.isEmpty()) break
                    if (nexts.size == 1) {
                        val next = BookRuleEngine.fetchPageWithOptions(nexts[0], ua, options = fetchOptions(source)) ?: break
                        page = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), next, ctx, state) {
                            BookRuleEngine.fetchPageWithOptions(nexts[0], ua, options = fetchOptions(source))
                        }
                        continue
                    }
                    for (u in nexts) {
                        val p = BookRuleEngine.fetchPageWithOptions(u, ua, options = fetchOptions(source)) ?: continue
                        val checked = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), p, ctx, state) {
                            BookRuleEngine.fetchPageWithOptions(u, ua, options = fetchOptions(source))
                        }
                        collect(checked.body, checked.url, listRule)
                    }
                    break
                }
            }
            harvest(curPage)
            // v1.2.25：目录兜底——首轮解析出 0 章时，跟页面上的“目录”链接再解析一次（与纯路径同口径）。
            var fallbackChecked: PageResult? = null
            if (all.isEmpty()) {
                val fallbackUrl = BookRuleEngine.findTocLink(curPage.body, curPage.url)
                if (fallbackUrl != null) {
                    val fbPage = BookRuleEngine.fetchPageWithOptions(fallbackUrl, ua, options = fetchOptions(source))
                    if (fbPage != null) {
                        val checked = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), fbPage, ctx, state) {
                            BookRuleEngine.fetchPageWithOptions(fallbackUrl, ua, options = fetchOptions(source))
                        }
                        fallbackChecked = checked
                        harvest(checked)
                        LogBus.d(TAG, "目录兜底 ${source.name} 跟进 $fallbackUrl 解析出 ${all.size} 章")
                    }
                }
            }
            // v1.2.28：通用兜底抽成小函数——a[href] 按书页目录过滤（≥3 条才用），
            // 不足改用老口径 //li/a；返回新增章数。话本实证：裸 li>a 全是导航/推荐/页脚。
            suspend fun harvestGeneric(page: PageResult): Int {
                val before = all.size
                harvest(page, "a[href]")
                val added = all.subList(before, all.size).toList()
                val kept = added.filter { BookRuleEngine.isSameBookChapterLink(it.url, detailUrl) }
                repeat(added.size) { all.removeAt(before) }
                if (kept.size >= 3) {
                    all.addAll(kept)
                    LogBus.d(TAG, "目录通用兜底 ${source.name} 用同书链接解析出 ${kept.size} 章")
                    return kept.size
                }
                harvest(page, "//li/a")
                val n = all.size - before
                if (n > 0) LogBus.d(TAG, "目录通用兜底 ${source.name} 用 li>a 解析出 $n 章")
                return n
            }
            // v1.2.26：通用列表兜底——源规则（含 XPath 转换）仍为 0 章时，用通用兜底再抓一次
            // （目录页优先：跟进过的目录页在前，详情页在后）。
            var usedGenericFallback = false
            if (all.isEmpty()) {
                for (p in listOfNotNull(fallbackChecked, curPage)) {
                    if (harvestGeneric(p) > 0) {
                        usedGenericFallback = true
                        break
                    }
                }
            }
            // v1.2.28：兜底出的目录可能不全（如话本详情页只 inline 43/57 章）；
            // 尚未跟进过目录页时，页上有“目录”链接就跟过去再抓一次，取章数多的那份。
            if (usedGenericFallback && fallbackChecked == null) {
                val moreUrl = BookRuleEngine.findTocLink(curPage.body, curPage.url)
                if (moreUrl != null && moreUrl != curPage.url) {
                    val morePage = BookRuleEngine.fetchPageWithOptions(moreUrl, ua, options = fetchOptions(source))
                    if (morePage != null) {
                        val checkedMore = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), morePage, ctx, state) {
                            BookRuleEngine.fetchPageWithOptions(moreUrl, ua, options = fetchOptions(source))
                        }
                        val genericCount = all.size
                        harvest(checkedMore)
                        var moreCount = all.size - genericCount
                        if (moreCount == 0) moreCount = harvestGeneric(checkedMore)
                        if (moreCount > genericCount) {
                            repeat(genericCount) { all.removeAt(0) }
                            LogBus.d(TAG, "目录兜底 ${source.name} 目录页 $moreCount 章多于详情页 $genericCount 章，用目录页")
                        } else {
                            repeat(all.size - genericCount) { all.removeAt(genericCount) }
                        }
                    }
                }
            }
            val result = all.filterNot { NovelStore.isNavChapterTitle(it.title) }
                .mapIndexed { i, m -> m.copy(index = i) }
            // v0.227.0：目录诊断——下次再空，直接看是详情没拿到、握手换了页、还是规则没命中。
            LogBus.d(TAG, "目录诊断 ${source.name} 详情长=$firstLen 握手后长=$checkedLen 解析出 ${result.size} 章")
            result
        }
    }

    /** v0.250.0：非 JS 正文提取（规则 + 替换 + 通用兜底），首抓与验证重抓共用。 */
    private fun extractChapterText(source: BookSource, html: String, pageUrl: String): String {
        // v1.2.28：JSON 路径正文规则（对标阅读 AnalyzeRule.getString）：
        // 裸 `$.` 规则不是 CSS 选择器——先按 JSON 路径对响应体求值，取出的
        // htmlContent 按 HTML 片段分段；路径取不到返回 ""（权威空，不回退
        // readabilityText——它对原始 JSON 文本提字会把 \r 转义以字面量漏上屏）。
        LegadoJsEngine.evalJsonPathRule(html, source.ruleContent)?.let { value ->
            if (value.isBlank()) return ""
            val text = if (value.contains('<')) BookRuleEngine.extractHtmlText(value, pageUrl) else value
            return if (source.ruleContentReplace.isBlank()) text
            else BookRuleEngine.applyReplaceRegex(text, source.ruleContentReplace)
        }
        val doc = BookRuleEngine.parseDoc(html, pageUrl)
        val raw = if (source.ruleContent.isBlank()) "" else BookRuleEngine.extractContent(doc, source.ruleContent)
        val out = if (source.ruleContentReplace.isBlank()) raw
        else BookRuleEngine.applyReplaceRegex(raw, source.ruleContentReplace)
        // v0.202.0：规则提空时用通用正文兜底（无 content 规则的源也由此可读）
        return out.ifBlank { readabilityText(pageUrl, html) }
    }

    /**
     * 拉单章正文。v0.198.0：JSON 源按 ruleContentReplace 做正文替换。v0.199.0：JS 源走 LegadoJsEngine（含正文分页）。
     * v0.250.0：正文拉取补齐三项不对称——①走 gatedFetch（403/空页时用户主动读可验证，
     * 预加载绝不弹验证页）；②走搜索同款 Semaphore(4)，验证等待本身不占名额；
     * ③返回失败分类，不再以空串抹掉 403/超时/DNS 的区别。超时/DNS 仍只靠底层
     * 拦截器退避重试，正文层不叠加。JS 链路只给首抓加限流与 Referer，登录校验语义不动。
     */
    suspend fun content(source: BookSource, chapter: ChapterMeta, allowGate: Boolean = false): ContentResult {
        applySourceNetConfig(source)
        if (chapter.url.isBlank()) return ContentResult.Failed(ContentFail.UNKNOWN)
        if (!isJsSource(source)) {
            // v0.217.0（F43）：非 JS 链路也要传源自定义 UA——原来只 JS 链路传，
            // CSS 源正文永远走默认 UA，需要自定义 UA 的站直接 403。
            val ua = uaOf(source)
            val referer = parentReferer(chapter.url)
            var fetchCount = 0
            var last: BookRuleEngine.FetchOutcome? = null
            val fetch: suspend () -> BookRuleEngine.FetchOutcome = {
                fetchCount++
                withSearchThrottle { BookRuleEngine.fetchPageOutcome(chapter.url, ua, referer, options = fetchOptions(source)) }.also {
                    last = it
                    // v0.250.0：异常已包成数据返回，取消语义不能跟着丢——任务已取消时
                    // 直接抛，不让取消的请求被分类成超时、也不让它继续走验证。
                    currentCoroutineContext().ensureActive()
                }
            }
            val page = gatedFetch(source, ua, chapter.url, allowGate, "正文", fetch)
                ?: return ContentResult.Failed(classifyContentFailure(last, fetchCount))
            val text = extractChapterText(source, page.body, page.url)
            if (text.isNotBlank()) return ContentResult.Ok(text)
            // 200 但提空：只有疑似验证页才走验证，普通规则不匹配不反复开验证。
            if (!VerifyGate.looksLikeChallenge(page.body)) return ContentResult.Failed(ContentFail.PARSE_EMPTY)
            if (!allowGate) return ContentResult.Failed(ContentFail.VERIFY)
            LogBus.w(TAG, "正文 ${source.name} 首抓 200 但疑似验证页，进验证")
            var refetched = false
            val page2 = verifyAndRefetch(source, ua, chapter.url, true, "正文") {
                refetched = true
                fetch()
            } ?: return ContentResult.Failed(
                if (refetched) classifyContentFailure(last, 2) else ContentFail.VERIFY,
            )
            val text2 = extractChapterText(source, page2.body, page2.url)
            return if (text2.isNotBlank()) ContentResult.Ok(text2)
            else ContentResult.Failed(ContentFail.PARSE_EMPTY)
        }
        return withContext(Dispatchers.IO) {
            val ua = uaOf(source)
            val referer = parentReferer(chapter.url)
            val state = LegadoJsEngine.JsState(source.key)
            val ctx = LegadoJsEngine.JsCtx(
                source = source.toJsRuleSource(),
                baseUrl = chapter.url,
                chapterTitle = chapter.title,
                chapterUrl = chapter.url,
            )
            val firstOutcome = withSearchThrottle { BookRuleEngine.fetchPageOutcome(chapter.url, ua, referer, options = fetchOptions(source)) }
            // v0.250.0：同非 JS 路径——取消的任务直接抛，不进失败分类。
            coroutineContext.ensureActive()
            val first = firstOutcome.page ?: return@withContext ContentResult.Failed(classifyContentFailure(firstOutcome, 1))
            val checked = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), first, ctx, state) {
                BookRuleEngine.fetchPage(chapter.url, ua, referer)
            }
            val sb = StringBuilder()
            var curBody = checked.body
            var curUrl = checked.url
            var guard = 0
            while (true) {
                sb.append(evalChapterText(source, curBody, curUrl, ctx, state))
                if (source.ruleContentNext.isBlank() || ++guard >= 20) break
                val pageCtx = ctx.copy(baseUrl = curUrl)
                val nexts = LegadoJsEngine.evalNextUrls(curBody, curUrl, source.ruleContentNext, pageCtx, state)
                if (nexts.isEmpty()) break
                if (nexts.size == 1) {
                    val p = BookRuleEngine.fetchPage(nexts[0], ua, options = fetchOptions(source)) ?: break
                    val c = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), p, ctx, state) {
                        BookRuleEngine.fetchPage(nexts[0], ua, options = fetchOptions(source))
                    }
                    curBody = c.body
                    curUrl = c.url
                    continue
                }
                for (u in nexts) {
                    val p = BookRuleEngine.fetchPage(u, ua, options = fetchOptions(source)) ?: continue
                    val c = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), p, ctx, state) {
                        BookRuleEngine.fetchPage(u, ua, options = fetchOptions(source))
                    }
                    sb.append(evalChapterText(source, c.body, c.url, ctx, state))
                }
                break
            }
            var outText = sb.toString()
            if (source.ruleContentReplace.isNotBlank()) {
                outText = if (source.ruleContentReplace.trimStart().startsWith("<js>")) {
                    val (js, _) = LegadoJsEngine.splitJsPrefix(source.ruleContentReplace)
                    if (js != null) {
                        LegadoJsEngine.toJsString(
                            LegadoJsEngine.evalScript(
                                LegadoJsEngine.substituteTemplates(js, ctx),
                                LegadoJsEngine.bindings(ctx, state, result = outText, src = outText),
                                LegadoJsEngine.jsLibPrelude(ctx.source),
                            ),
                        )
                    } else {
                        outText
                    }
                } else {
                    BookRuleEngine.applyReplaceRegex(outText, source.ruleContentReplace)
                }
            }
            // v0.202.0：JS 规则提空时同样用通用正文兜底（取首页面源码）。
            // v1.2.28：JSON 路径规则提空是权威空（对标阅读 AnalyzeRule 取不到
            // 即内容为空），不再回退 readabilityText——它对原始 JSON 文本提字
            // 会把 \r 转义以字面量漏上屏（黑岩 #破冰实证）。
            val isJsonContentRule = LegadoJsEngine.splitJsonRule(source.ruleContent) != null
            val finalText =
                if (outText.isBlank() && !isJsonContentRule) readabilityText(chapter.url, checked.body)
                else outText
            if (finalText.isNotBlank()) ContentResult.Ok(finalText)
            else ContentResult.Failed(ContentFail.PARSE_EMPTY)
        }
    }

    /**
     * v1.1.10：段评结构化版——拉本章逐段评论。
     *
     * 规则为空 → 空列表（不发请求）；先查 sidecar 缓存（24 小时 TTL），命中直接回；
     * 未命中按规则求值：<js> 前缀跑 Rhino（java.ajax 同步抓接口，规则须返回 JSON
     * 数组字符串，见 ParagraphCommentStore.parseJson），否则抓章节页按 CSS
     * 规则提 JSON 串。解析失败记日志回空列表、不缓存、不抛。
     * 只看不写：不调站方发表接口。Rhino 求值与 java.ajax 都是同步阻塞，须在 IO 线程。
     */
    suspend fun paragraphComments(
        context: Context,
        source: BookSource,
        novelId: String,
        chapter: ChapterMeta,
        allowGate: Boolean = false,
    ): List<ParagraphComment> {
        if (source.ruleParagraphComments.isBlank() || chapter.url.isBlank()) return emptyList()
        ParagraphCommentStore.load(context, novelId, chapter.index)?.let { return it }
        val jsonStr = withContext(Dispatchers.IO) {
            runCatching { evaluateParagraphComments(source, chapter, allowGate) }
                .onFailure {
                    if (it is CancellationException) throw it
                    LogBus.w(TAG, "段评求值失败 ${source.name}: ${it.message}")
                }
                .getOrDefault("")
        }
        val parsed = ParagraphCommentStore.parseJson(jsonStr) ?: return emptyList()
        ParagraphCommentStore.save(context, novelId, chapter.index, parsed)
        return parsed
    }

    /** v1.1.10：段评规则求值（JS 跑 Rhino，CSS 抓章节页提取；失败返回空串）。 */
    private suspend fun evaluateParagraphComments(
        source: BookSource,
        chapter: ChapterMeta,
        allowGate: Boolean,
    ): String {
        val rule = source.ruleParagraphComments.trim()
        val (js, _) = LegadoJsEngine.splitJsPrefix(rule)
        if (js != null) {
            val state = LegadoJsEngine.JsState(source.key)
            val ctx = LegadoJsEngine.JsCtx(
                source = source.toJsRuleSource(),
                baseUrl = chapter.url,
                bookUrl = chapter.url,
                chapterTitle = chapter.title,
                chapterUrl = chapter.url,
            )
            val out = LegadoJsEngine.evalScript(
                LegadoJsEngine.substituteTemplates(js, ctx),
                LegadoJsEngine.bindings(ctx, state, result = "", src = ""),
                LegadoJsEngine.jsLibPrelude(ctx.source),
            )
            return LegadoJsEngine.toJsString(out)
        }
        val page = gatedFetch(source, uaOf(source), chapter.url, allowGate, "段评") {
            BookRuleEngine.fetchPageOutcome(chapter.url, uaOf(source), options = fetchOptions(source))
        } ?: return ""
        val doc = BookRuleEngine.parseDoc(page.body, page.url)
        return BookRuleEngine.extract(doc, page.url, rule)
    }

    /**
     * v0.202.0：通用正文兜底（Readability4J，Mozilla Readability 的 Kotlin 移植，
     * Apache 2.0）：规则提空时从整页 HTML 提正文。失败返回空，不抛。
     */
    private fun readabilityText(pageUrl: String, html: String): String {
        if (html.isBlank()) return ""
        return runCatching {
            net.dankito.readability4j.Readability4J(pageUrl, html).parse().textContent.orEmpty()
        }.getOrDefault("")
    }

    /** v0.199.0：单页正文求值（JS 规则跑 Rhino，否则走老 extractContent）。
     * v1.2.28：JSON 路径正文规则（对标阅读 AnalyzeRule.getString）：
     * `$.chapter.htmlContent@js:...` 先按 JSON 路径求值（JSON 解码把 `\r`
     * 转义还原成真实回车），再跑 @js: 后缀，取出的 HTML 片段按块级分段。
     * 以前这条路走 extractContent，Jsoup 对 `$` 开头的选择器直接抛错返回
     * 空串，content() 再回退 readabilityText 对原始 JSON 文本提字，
     * `\r` 转义以字面量漏上屏（黑岩 #破冰实证）。路径取不到返回 ""（权威空）。 */
    private fun evalChapterText(
        source: BookSource,
        html: String,
        pageUrl: String,
        ctx: LegadoJsEngine.JsCtx,
        state: LegadoJsEngine.JsState,
    ): String {
        val (js, rest) = LegadoJsEngine.splitJsPrefix(source.ruleContent)
        if (js != null) {
            state.currentSrc = html
            val out = LegadoJsEngine.evalScript(
                LegadoJsEngine.substituteTemplates(js, ctx),
                LegadoJsEngine.bindings(ctx.copy(baseUrl = pageUrl), state, result = "", src = html),
                LegadoJsEngine.jsLibPrelude(ctx.source),
            )
            var s = LegadoJsEngine.toJsString(out)
            if (rest.isNotBlank()) {
                val doc = BookRuleEngine.parseDoc(s, pageUrl)
                s = LegadoJsEngine.evalTextRule(doc, pageUrl, rest, ctx.copy(baseUrl = pageUrl), state)
            }
            return s
        }
        LegadoJsEngine.evalJsonRule(html, source.ruleContent, ctx, state)?.let { value ->
            if (value.isBlank()) return ""
            return if (value.contains('<')) BookRuleEngine.extractHtmlText(value, pageUrl) else value
        }
        val doc = BookRuleEngine.parseDoc(html, pageUrl)
        return BookRuleEngine.extractContent(doc, source.ruleContent)
    }

    /**
     * v0.200.0：详情封面求值：ruleBookCover 为空返回 null；含 <js> 时跑 Rhino
     * （如 999 的 uaa001 接口取封面，JS 里的 {{book.name}} 已替换），余下可接 JSON 路径；
     * 纯 CSS 封面规则也同样走这条（先抓详情页再按链取）。
     * 仅在搜索结果封面为空时由入架流程调用，不影响已有封面。
     */
    suspend fun detailCover(source: BookSource, book: NovelSearchResult): String? {
        applySourceNetConfig(source)
        if (source.ruleBookCover.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val page = BookRuleEngine.fetchPage(book.remoteId, uaOf(source), options = fetchOptions(source)) ?: return@withContext null
            val ctx = LegadoJsEngine.JsCtx(
                source = source.toJsRuleSource(),
                baseUrl = page.url,
                bookName = book.title,
                bookUrl = book.remoteId,
                bookAuthor = book.author.orEmpty(),
            )
            val state = LegadoJsEngine.JsState(source.key)
            val checked = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), page, ctx, state) {
                BookRuleEngine.fetchPage(book.remoteId, uaOf(source), options = fetchOptions(source))
            }
            val doc = BookRuleEngine.parseDoc(checked.body, checked.url)
            LegadoJsEngine.evalTextRule(doc, checked.url, source.ruleBookCover, ctx.copy(baseUrl = checked.url), state)
                .ifBlank { null }
        }
    }

    // v1.1.6：BookDetailInfo 已移入 domain/model（NovelModels.kt）。

    suspend fun bookDetail(source: BookSource, book: NovelSearchResult): BookDetailInfo {
        applySourceNetConfig(source)
        if (book.remoteId.isBlank()) return BookDetailInfo()
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!isJsSource(source)) {
                    val html = BookRuleEngine.getHtml(book.remoteId, options = fetchOptions(source)) ?: return@runCatching BookDetailInfo()
                    val doc = BookRuleEngine.parseDoc(html, book.remoteId)
                    fun ex(rule: String) =
                        if (rule.isBlank()) "" else BookRuleEngine.extract(doc, book.remoteId, rule)
                    BookDetailInfo(
                        name = ex(source.ruleBookName),
                        author = ex(source.ruleBookAuthor),
                        intro = ex(source.ruleBookIntro),
                        cover = ex(source.ruleBookCover),
                        kind = ex(source.ruleBookKind),
                    )
                } else {
                    val page = BookRuleEngine.fetchPage(book.remoteId, uaOf(source), options = fetchOptions(source))
                        ?: return@runCatching BookDetailInfo()
                    val ctx = LegadoJsEngine.JsCtx(
                        source = source.toJsRuleSource(),
                        baseUrl = page.url,
                        bookName = book.title,
                        bookUrl = book.remoteId,
                        bookAuthor = book.author.orEmpty(),
                    )
                    val state = LegadoJsEngine.JsState(source.key)
                    val checked = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), page, ctx, state) {
                        BookRuleEngine.fetchPage(book.remoteId, uaOf(source), options = fetchOptions(source))
                    }
                    val doc = BookRuleEngine.parseDoc(checked.body, checked.url)
                    val c2 = ctx.copy(baseUrl = checked.url)
                    fun ev(rule: String) =
                        if (rule.isBlank()) "" else LegadoJsEngine.evalTextRule(doc, checked.url, rule, c2, state)
                    BookDetailInfo(
                        name = ev(source.ruleBookName),
                        author = ev(source.ruleBookAuthor),
                        intro = ev(source.ruleBookIntro),
                        cover = ev(source.ruleBookCover),
                        kind = ev(source.ruleBookKind),
                    )
                }
            }.onFailure {
                // v0.230.0：取消不算失败——页面离开导致的效果取消此前被当成抓取失败记了一笔噪音，原样抛出让协程正常取消。
                if (it is CancellationException) throw it
                LogBus.w(TAG, "详情抓取失败 ${book.remoteId}: ${it.message}")
            }
                .getOrDefault(BookDetailInfo())
        }
    }

    /**
     * v0.201.0：发现页——按分类 URL 抓一页，返回书目。
     * 规则取 ruleExplore，缺失项回落到 ruleSearch（源只有 exploreUrl 没写
     * ruleExplore 时也能用）；分类 URL 支持 {{page}} 模板与相对地址（按源地址补全）。
     */
    suspend fun explore(source: BookSource, categoryUrl: String, page: Int, allowGate: Boolean = false): List<NovelSearchResult> {
        applySourceNetConfig(source)
        if (source.exploreUrl.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val url = resolveUrl(source.url, categoryUrl.replace("{{page}}", page.toString()))
            val ua = uaOf(source)
            val first = gatedFetch(source, ua, url, allowGate) {
                BookRuleEngine.fetchPageOutcome(url, ua, options = fetchOptions(source))
            } ?: return@withContext emptyList()
            val state = LegadoJsEngine.JsState(source.key)
            val ctx = LegadoJsEngine.JsCtx(source = source.toJsRuleSource(), baseUrl = first.url, page = page)
            val checked = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), first, ctx, state) {
                BookRuleEngine.fetchPage(url, ua, options = fetchOptions(source))
            }
            val listRule = source.ruleExploreList.ifBlank { source.ruleSearchList }
            if (listRule.isBlank()) return@withContext emptyList()
            val nameRule = source.ruleExploreName.ifBlank { source.ruleSearchName.ifBlank { "@text" } }
            val authorRule = source.ruleExploreAuthor.ifBlank { source.ruleSearchAuthor }
            val bookUrlRule = source.ruleExploreBookUrl.ifBlank { source.ruleSearchBookUrl.ifBlank { "a@href" } }
            val coverRule = source.ruleExploreCover.ifBlank { source.ruleSearchCover }
            LegadoJsEngine.evalListRule(checked.body, checked.url, listRule, ctx, state).mapNotNull { el ->
                val name = LegadoJsEngine.evalTextRule(el, checked.url, nameRule, ctx, state)
                val bookUrl = LegadoJsEngine.evalTextRule(el, checked.url, bookUrlRule, ctx, state)
                if (name.isBlank() || bookUrl.isBlank()) return@mapNotNull null
                NovelSearchResult(
                    sourceKey = source.key,
                    remoteId = absUrl(checked.url, bookUrl),
                    title = name,
                    author = LegadoJsEngine.evalTextRule(el, checked.url, authorRule, ctx, state).ifBlank { null },
                    cover = LegadoJsEngine.evalTextRule(el, checked.url, coverRule, ctx, state).ifBlank { null },
                )
            }
        }
    }

    /** v0.210.0：发现页原网页直渲用：分类 URL 补成完整地址（第 1 页）。 */
    fun explorePageUrl(source: BookSource, categoryUrl: String): String =
        resolveUrl(source.url, categoryUrl.replace("{{page}}", "1"))

    /** 相对地址按源地址补全（源地址尾斜杠与分类地址首斜杠不重复）。 */
    private fun resolveUrl(base: String, url: String): String {
        if (url.startsWith("http")) return url
        val b = base.trimEnd('/')
        val u = if (url.startsWith("/")) url else "/$url"
        return b + u
    }

    /** 发现分类。 */
    data class ExploreCategory(val title: String, val url: String)

    /**
     * 解析 exploreUrl：数组形 [{title,url}] 或对象形 {标题: url | {url}}；
     * 空地址的分组标题行跳过。解析失败返回空列表，不抛。
     */
    fun parseExploreCategories(exploreUrl: String): List<ExploreCategory> {
        val t = exploreUrl.trim()
        if (t.isEmpty()) return emptyList()
        return runCatching {
            if (t.startsWith("[")) {
                val arr = JSONArray(t)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val title = o.optString("title")
                    val url = o.optString("url")
                    if (title.isBlank() || url.isBlank()) null else ExploreCategory(title, url)
                }
            } else {
                val o = JSONObject(t)
                o.keys().asSequence().mapNotNull { title ->
                    val v = o.opt(title)
                    val url = when (v) {
                        is String -> v
                        is JSONObject -> v.optString("url")
                        else -> ""
                    }
                    if (url.isBlank()) null else ExploreCategory(title, url)
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    // ---------------- v0.64.0 本地 PY 书源链路 ----------------
    // PY 书源的 NovelSearchResult.sourceKey 为 "pybook:<文件名>"，
    // 凭此外缀找到规则文件，走 PyBookApi 调 bookSearch/bookDetail/bookChapter。

    private val pyBookApi = PyBookApi()

    /** v1.1.6 分层第 2 批：JSON 书源的 NovelEngine（绑站适配器）。
     * v1.1.10：构造带 Context——段评 sidecar 缓存要落 filesDir。 */
    fun novelEngineFor(context: Context, source: BookSource): NovelEngine = JsonNovelEngine(context, source)

    /** v1.1.6 分层第 2 批：PY 书源的 NovelEngine（绑规则适配器）。 */
    fun novelEngineForPy(context: Context, rule: PyBookSourceManager.PyBookRule): NovelEngine =
        PyNovelEngine(context, rule)

    /**
     * v1.1.6 分层第 2 批：凭 sourceKey 拿引擎（"pybook:" 前缀走 PY 规则，
     * 否则走 JSON 书源）；两边注册表里都找不到回 null。
     * 调用前确保 BookSourceManager / PyBookSourceManager 已 load。
     */
    fun novelEngineForKey(context: Context, sourceKey: String): NovelEngine? {
        return if (sourceKey.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)) {
            val name = PyBookSourceManager.fileNameOf(sourceKey) ?: return null
            val rule = PyBookSourceManager.rules.value.firstOrNull { it.name == name } ?: return null
            PyNovelEngine(context, rule)
        } else {
            val source = BookSourceManager.sources.value.firstOrNull { it.key == sourceKey } ?: return null
            JsonNovelEngine(context, source)
        }
    }

    /** 拉 PY 书源的详情（名/作者/简介/封面），供 PyNovelEngine.bookDetail。 */
    suspend fun bookDetailPy(context: Context, book: NovelSearchResult): BookDetailInfo {
        val path = pyRulePath(context, book) ?: return BookDetailInfo()
        return runCatching { pyBookApi.detail(path, book.remoteId) }
            .onFailure {
                if (it is CancellationException) throw it
                LogBus.w(TAG, "PY 书源详情失败 ${book.sourceKey}: ${it.message}")
            }
            .getOrNull()
            ?.let {
                BookDetailInfo(
                    name = it.name,
                    author = it.author.orEmpty(),
                    intro = it.intro,
                    cover = it.cover.orEmpty(),
                )
            }
            ?: BookDetailInfo()
    }

    private fun pyRulePath(context: Context, book: NovelSearchResult): String? {
        val name = PyBookSourceManager.fileNameOf(book.sourceKey) ?: return null
        return PyBookSourceManager.rulePath(context, name)
    }

    /** 在单个 PY 书源里搜书。 */
    suspend fun searchPy(
        context: Context,
        rule: PyBookSourceManager.PyBookRule,
        keyword: String,
    ): List<NovelSearchResult> {
        val path = PyBookSourceManager.rulePath(context, rule.name) ?: return emptyList()
        return runCatching { pyBookApi.search(path, keyword) }
            .onFailure {
                // v0.247.0（取消语义）：同 searchAll，取消原样抛出。
                if (it is CancellationException) throw it
                // v1.1.12：记一笔失败，搜书页据此区分“真没书”和“搜失败了”。
                failedSearchNames.add(rule.name)
                LogBus.w(TAG, "PY 书源搜索失败 ${rule.name}: ${it.message}")
            }
            .getOrDefault(emptyList())
            .map { it.copy(sourceKey = PyBookSourceManager.SOURCE_KEY_PREFIX + rule.name) }
    }

    /** 多个 PY 书源并发搜，结果按源分组返回。v0.101.0：Semaphore(4) 限流防反爬封 IP。 */
    suspend fun searchAllPy(
        context: Context,
        rules: List<PyBookSourceManager.PyBookRule>,
        keyword: String,
    ): List<Pair<PyBookSourceManager.PyBookRule, List<NovelSearchResult>>> = coroutineScope {
        rules.map { r ->
            async { r to searchSemaphore.withPermit { searchPy(context, r, keyword) } }
        }.awaitAll()
    }

    /**
     * v0.109.0：统一搜索限流入口。searchAll/searchAllPy 之外的搜索路径
     * （如追更检查 checkAll）也走这个，避免直调 search/searchPy 绕过 Semaphore(4)
     * 被反爬封 IP。
     */
    suspend fun <T> withSearchThrottle(block: suspend () -> T): T =
        searchSemaphore.withPermit { block() }

    /** 拉 PY 书源的目录。 */
    suspend fun chaptersPy(context: Context, book: NovelSearchResult): List<ChapterMeta> {
        val path = pyRulePath(context, book) ?: return emptyList()
        return runCatching { pyBookApi.detail(path, book.remoteId)?.chapters.orEmpty() }
            .onFailure {
                // v0.247.0（取消语义）：同 searchAll，取消原样抛出。
                if (it is CancellationException) throw it
                LogBus.w(TAG, "PY 书源目录失败 ${book.sourceKey}: ${it.message}")
            }
            .getOrDefault(emptyList())
            // v0.194.0：导航章兜底过滤（见 NovelStore.isNavChapterTitle）。
            .filterNot { NovelStore.isNavChapterTitle(it.title) }
            .mapIndexed { i, m -> m.copy(index = i) }
    }

    /** 拉 PY 书源的单章正文。 */
    suspend fun contentPy(context: Context, chapter: ChapterMeta, sourceKey: String): String {
        val name = PyBookSourceManager.fileNameOf(sourceKey) ?: return ""
        val path = PyBookSourceManager.rulePath(context, name) ?: return ""
        if (chapter.url.isBlank()) return ""
        return runCatching { pyBookApi.chapter(path, chapter.url) }
            .onFailure {
                // v0.247.0（取消语义）：同 searchAll，取消原样抛出。
                if (it is CancellationException) throw it
                LogBus.w(TAG, "PY 书源正文失败 $sourceKey: ${it.message}")
            }
            .getOrDefault("")
    }

    /** v0.294.55：JS/链规则算出的相对 bookUrl（如 /b/123.html）按页面地址补全，
     * 否则 fetchPage 拿相对地址请求必失败，详情/目录打不开。 */
    private fun absUrl(pageUrl: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return runCatching { java.net.URL(java.net.URL(pageUrl), href).toString() }.getOrDefault(href)
    }
}
