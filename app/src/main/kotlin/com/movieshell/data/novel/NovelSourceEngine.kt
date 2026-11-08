package com.movieshell.data.novel

import android.content.Context
import com.movieshell.core.js.LegadoJsEngine
import com.movieshell.core.log.LogBus
import com.movieshell.core.net.HttpClient
import com.movieshell.core.net.PageResult
import com.movieshell.core.net.SourceThrottle
import com.movieshell.core.net.buildSearchRequest
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * v0.61.0 书源执行引擎：把 BookSource 规则跑通
 * 搜索 → 书籍详情 → 目录 → 章节正文。
 *
 * 所有网络 IO 走 BookRuleEngine；失败吞掉打日志，返回空。
 */
object NovelSourceEngine {

    private const val TAG = "NovelSourceEngine"

    /** v0.101.0：书源并发搜上限，书源齐发会被反爬封 IP（影视模块有自己的限流，互不干扰）。 */
    private const val SEARCH_MAX_CONCURRENCY = 4
    private val searchSemaphore = Semaphore(SEARCH_MAX_CONCURRENCY)

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

    /** v0.199.0：searchUrl 求值：<js> 前缀跑 JS，否则原样（{{key}}/{{page}} 由 buildSearchRequest 处理）。 */
    private fun evalSearchUrl(
        source: BookSource,
        ctx: LegadoJsEngine.JsCtx,
        state: LegadoJsEngine.JsState,
    ): String? {
        val (js, _) = LegadoJsEngine.splitJsPrefix(source.searchUrl)
        if (js == null) return source.searchUrl
        val out = LegadoJsEngine.evalScript(
            js,
            LegadoJsEngine.bindings(ctx, state, result = "", src = ""),
            LegadoJsEngine.jsLibPrelude(ctx.source),
        )
        val urlStr = LegadoJsEngine.toJsString(out)
        // v0.226.0：求值失败时旧逻辑回退原串，OkHttp 再以“无 scheme”炸掉，
        // 白白走 30 秒验证；求值失败直接判空，调用方跳过验证。
        if (urlStr.isBlank() || urlStr.trimStart().startsWith("<js>")) {
            LogBus.w(TAG, "搜索地址 JS 求值失败 ${source.name}，跳过本次搜索")
            return null
        }
        return urlStr
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

    suspend fun search(source: BookSource, keyword: String, allowGate: Boolean = false): List<NovelSearchResult> {
        applySourceNetConfig(source)
        if (source.searchUrl.isBlank() || source.ruleSearchList.isBlank()) return emptyList()
        if (!isJsSource(source)) {
            val req = buildSearchRequest(source.searchUrl, keyword)
            val html = gatedFetch(source, null, source.url, allowGate) {
                BookRuleEngine.fetchSearchPageOutcome(req, options = fetchOptions(source))
            }?.body ?: return emptyList()
            val url = req.url
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
            }
        }
        return withContext(Dispatchers.IO) {
            val ua = uaOf(source)
            val state = LegadoJsEngine.JsState(source.key)
            val ctx = LegadoJsEngine.JsCtx(source = source.toJsRuleSource(), baseUrl = source.url, keyword = keyword, page = 1)
            val urlStr = evalSearchUrl(source, ctx, state) ?: return@withContext emptyList()
            val req = buildSearchRequest(urlStr, keyword)
            var page = gatedFetch(source, ua, source.url, allowGate) {
                BookRuleEngine.fetchSearchPageOutcome(req, ua, options = fetchOptions(source))
            }
            if (page == null) {
                // v0.223.0：搜书诊断——未抓到页以前是静默空，记一行才能分清是请求问题还是解析问题。
                LogBus.w(TAG, "搜书诊断 ${source.name} 未抓到页")
                return@withContext emptyList()
            }
            // v0.209.0：登录后重抓也过验证通道——999 这类源先握手再被墙时，
            // 原直接重抓拿不到页又不弹窗，搜索静默为空。
            val beforeLoginLen = page.body.length
            page = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), page, ctx, state, refetch = {
                val urlStr2 = evalSearchUrl(source, ctx, state)
                if (urlStr2 == null) {
                    null
                } else {
                    val req2 = buildSearchRequest(urlStr2, keyword)
                    gatedFetch(source, ua, source.url, allowGate) {
                        BookRuleEngine.fetchSearchPageOutcome(req2, ua, options = fetchOptions(source))
                    }
                }
            })
            val pageCtx = ctx.copy(baseUrl = page.url)
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
            }
        }
    }

    /** 多源并发搜，结果按源分组返回。v0.101.0：Semaphore(4) 限流防反爬封 IP。 */
    suspend fun searchAll(
        sources: List<BookSource>,
        keyword: String,
        allowGate: Boolean = false,
    ): List<Pair<BookSource, List<NovelSearchResult>>> = coroutineScope {
        sources.map { s ->
            async {
                s to searchSemaphore.withPermit {
                    runCatching { search(s, keyword, allowGate) }
                        .onFailure {
                            // v0.247.0（取消语义）：取消不算源失败——原样抛出，
                            // 让协程正常取消（v0.230.0 bookDetail 同款）；否则旧搜索
                            // 被取消后仍正常返回空结果，覆盖新搜索的结果。
                            if (it is CancellationException) throw it
                            LogBus.w(TAG, "搜索失败 ${s.name}: ${it.message}")
                        }
                        .getOrDefault(emptyList())
                }
            }
        }.awaitAll()
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
            val tocPage = BookRuleEngine.fetchPageWithOptions(tocUrl, options = fetchOptions(source)) ?: return emptyList()
            // 目录页可能是详情页（规则里 toc 列表直接在详情页上）
            val doc = BookRuleEngine.parseDoc(tocPage.body, tocPage.url)
            val listRule = source.ruleTocList.ifBlank { return emptyList() }
            val items = runCatching { doc.select(listRule).toList() }.getOrDefault(emptyList())
            return items.mapIndexedNotNull { i, el ->
                val title = BookRuleEngine.extract(el, tocPage.url, source.ruleTocTitle.ifBlank { "@text" })
                val url = BookRuleEngine.extract(el, tocPage.url, source.ruleTocUrl.ifBlank { "@href" })
                if (title.isBlank() || url.isBlank()) return@mapIndexedNotNull null
                // v1.1.3：段评最小版——提评论页地址（规则空时 extract 返回空串，不参与过滤）。
                val commentUrl = BookRuleEngine.extract(el, tocPage.url, source.ruleTocCommentUrl)
                ChapterMeta(index = i, title = title, url = url, commentUrl = commentUrl)
            }
                // v0.194.0：导航章兜底过滤（见 NovelStore.isNavChapterTitle）。
                .filterNot { NovelStore.isNavChapterTitle(it.title) }
                .mapIndexed { i, m -> m.copy(index = i) }
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
            fun collect(html: String, pageUrl: String) {
                val pageCtx = ctx.copy(baseUrl = pageUrl)
                LegadoJsEngine.evalListRule(html, pageUrl, source.ruleTocList, pageCtx, state).forEach { el ->
                    val title = LegadoJsEngine.evalTextRule(el, pageUrl, source.ruleTocTitle.ifBlank { "@text" }, pageCtx, state)
                    val url = LegadoJsEngine.evalTextRule(el, pageUrl, source.ruleTocUrl.ifBlank { "@href" }, pageCtx, state)
                    if (title.isNotBlank() && url.isNotBlank()) {
                        // v1.1.3：段评最小版——提评论页地址（规则空时 evalTextRule 返回空串）。
                        val commentUrl = LegadoJsEngine.evalTextRule(el, pageUrl, source.ruleTocCommentUrl, pageCtx, state)
                        all += ChapterMeta(index = all.size, title = title, url = url, commentUrl = commentUrl)
                    }
                }
            }
            var guard = 0
            while (guard++ < 50) {
                collect(curPage.body, curPage.url)
                if (source.ruleTocNext.isBlank()) break
                val pageCtx = ctx.copy(baseUrl = curPage.url)
                val nexts = LegadoJsEngine.evalNextUrls(curPage.body, curPage.url, source.ruleTocNext, pageCtx, state)
                if (nexts.isEmpty()) break
                if (nexts.size == 1) {
                    val next = BookRuleEngine.fetchPageWithOptions(nexts[0], ua, options = fetchOptions(source)) ?: break
                    curPage = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), next, ctx, state) {
                        BookRuleEngine.fetchPageWithOptions(nexts[0], ua, options = fetchOptions(source))
                    }
                    continue
                }
                for (u in nexts) {
                    val p = BookRuleEngine.fetchPageWithOptions(u, ua, options = fetchOptions(source)) ?: continue
                    val checked = LegadoJsEngine.runLoginCheck(source.toJsRuleSource(), p, ctx, state) {
                        BookRuleEngine.fetchPageWithOptions(u, ua, options = fetchOptions(source))
                    }
                    collect(checked.body, checked.url)
                }
                break
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
            // v0.202.0：JS 规则提空时同样用通用正文兜底（取首页面源码）
            val finalText = outText.ifBlank { readabilityText(chapter.url, checked.body) }
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

    /** v0.199.0：单页正文求值（JS 规则跑 Rhino，否则走老 extractContent）。 */
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
