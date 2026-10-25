package com.movieshell.data.novel

import android.content.Context
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.source.CmsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

    /** v0.199.0：该源是否用到 JS 能力（是则走 LegadoJsEngine，否则走老 CSS 路径，原样不动）。 */
    private fun isJsSource(source: BookSource): Boolean = LegadoJsEngine.isJsSource(source)

    private fun uaOf(source: BookSource): String? = source.headerUa.ifBlank { null }

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
            LegadoJsEngine.jsLibPrelude(source),
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
        fetch: suspend () -> BookRuleEngine.FetchOutcome,
    ): BookRuleEngine.PageResult? {
        val first = fetch()
        if (first.page != null) return first.page
        // v0.209.0：用户主动发起时，空页即弹验证——墙的拦法不止 403（沙箱测得 403，
        // 手机 VPN 下常表现为连接被重置/超时，httpCode 不是 403），只认 403 会漏弹。
        // 后台（allowGate=false）仍不弹。只重抓一次，不递归。
        // v0.210.0：先后台验证（不弹页面，挑战自己过了直接重抓）；后台过不了
        // （真人验证码、超时）再弹可见验证页兜底。
        if (!allowGate) return null
        // v0.225.0：全链路诊断——首抓、后台验证、重抓三处结果全记进悬浮窗，
        // 只记状态码/长度/有无 cf_clearance，不记正文与 Cookie 值。
        LogBus.w(TAG, "搜书抓取 ${source.name} 首抓空 httpCode=${first.httpCode}，进验证")
        val bg = runCatching { VerifyGate.awaitVerifyBackground(gateUrl, ua) }.getOrNull()
        LogBus.d(TAG, "搜书验证 ${source.name} 后台=${if (bg == null) "失败" else "通过 cf=${bg.cookieHeader.contains("cf_clearance=")} htmlLen=${bg.html.length} cookieLen=${bg.cookieHeader.length}"}")
        val result = bg ?: runCatching { VerifyGate.awaitVerify(gateUrl, source.name, ua) }.getOrNull()
        if (result == null) {
            LogBus.w(TAG, "搜书验证 ${source.name} 可见验证未通过")
            return null
        }
        if (result.cookieHeader.isNotBlank()) CmsApi.putCookies(result.url, result.cookieHeader)
        val again = fetch()
        if (again.page == null) LogBus.w(TAG, "搜书抓取 ${source.name} 重抓空 httpCode=${again.httpCode}")
        return again.page
    }

    suspend fun search(source: BookSource, keyword: String, allowGate: Boolean = false): List<NovelSearchResult> {
        if (source.searchUrl.isBlank() || source.ruleSearchList.isBlank()) return emptyList()
        if (!isJsSource(source)) {
            val req = BookRuleEngine.buildSearchRequest(source.searchUrl, keyword)
            val html = gatedFetch(source, null, source.url, allowGate) {
                BookRuleEngine.fetchSearchPageOutcome(req)
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
            val ctx = LegadoJsEngine.JsCtx(source = source, baseUrl = source.url, keyword = keyword, page = 1)
            val urlStr = evalSearchUrl(source, ctx, state) ?: return@withContext emptyList()
            val req = BookRuleEngine.buildSearchRequest(urlStr, keyword)
            var page = gatedFetch(source, ua, source.url, allowGate) {
                BookRuleEngine.fetchSearchPageOutcome(req, ua)
            }
            if (page == null) {
                // v0.223.0：搜书诊断——未抓到页以前是静默空，记一行才能分清是请求问题还是解析问题。
                LogBus.w(TAG, "搜书诊断 ${source.name} 未抓到页")
                return@withContext emptyList()
            }
            // v0.209.0：登录后重抓也过验证通道——999 这类源先握手再被墙时，
            // 原直接重抓拿不到页又不弹窗，搜索静默为空。
            val beforeLoginLen = page.body.length
            page = LegadoJsEngine.runLoginCheck(source, page, ctx, state, refetch = {
                val urlStr2 = evalSearchUrl(source, ctx, state)
                if (urlStr2 == null) {
                    null
                } else {
                    val req2 = BookRuleEngine.buildSearchRequest(urlStr2, keyword)
                    gatedFetch(source, ua, source.url, allowGate) {
                        BookRuleEngine.fetchSearchPageOutcome(req2, ua)
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
                    remoteId = bookUrl,
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
                        .onFailure { LogBus.w(TAG, "搜索失败 ${s.name}: ${it.message}") }
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
        val detailUrl = book.remoteId.ifBlank { return emptyList() }
        if (!isJsSource(source)) {
            var tocUrl = detailUrl
            if (source.ruleBookTocUrl.isNotBlank()) {
                val detailHtml = BookRuleEngine.getHtml(detailUrl) ?: return emptyList()
                val resolved = BookRuleEngine.extract(
                    BookRuleEngine.parseDoc(detailHtml, detailUrl),
                    detailUrl,
                    source.ruleBookTocUrl,
                )
                if (resolved.isNotBlank()) tocUrl = resolved
            }
            val html = BookRuleEngine.getHtml(tocUrl) ?: return emptyList()
            // 目录页可能是详情页（规则里 toc 列表直接在详情页上）
            val doc = BookRuleEngine.parseDoc(html, tocUrl)
            val listRule = source.ruleTocList.ifBlank { return emptyList() }
            val items = runCatching { doc.select(listRule).toList() }.getOrDefault(emptyList())
            return items.mapIndexedNotNull { i, el ->
                val title = BookRuleEngine.extract(el, tocUrl, source.ruleTocTitle.ifBlank { "@text" })
                val url = BookRuleEngine.extract(el, tocUrl, source.ruleTocUrl.ifBlank { "@href" })
                if (title.isBlank() || url.isBlank()) return@mapIndexedNotNull null
                ChapterMeta(index = i, title = title, url = url)
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
                source = source,
                baseUrl = detailUrl,
                bookName = book.title,
                bookUrl = book.remoteId,
                bookAuthor = book.author.orEmpty(),
            )
            var tocUrl = detailUrl
            if (source.ruleBookTocUrl.isNotBlank()) {
                val detailPage = BookRuleEngine.fetchPage(detailUrl, ua) ?: return@withContext emptyList()
                val checked = LegadoJsEngine.runLoginCheck(source, detailPage, ctx, state) {
                    // v0.227.0：握手后必须重抓——999 的 1234 握手把页换成
                    // “success”短串，refetch 为 null 时这页被当成详情页解析，
                    // 目录永远为空（搜书 136 行有 refetch 才正常，目录四处漏了）。
                    BookRuleEngine.fetchPage(detailUrl, ua)
                }
                val doc = BookRuleEngine.parseDoc(checked.body, checked.url)
                val resolved = LegadoJsEngine.evalTextRule(doc, checked.url, source.ruleBookTocUrl, ctx, state)
                if (resolved.isNotBlank()) tocUrl = resolved
            }
            val firstPage = BookRuleEngine.fetchPage(tocUrl, ua) ?: return@withContext emptyList()
            val firstLen = firstPage.body.length
            var curPage = LegadoJsEngine.runLoginCheck(source, firstPage, ctx, state) {
                BookRuleEngine.fetchPage(tocUrl, ua)
            }
            val checkedLen = curPage.body.length
            val all = mutableListOf<ChapterMeta>()
            fun collect(html: String, pageUrl: String) {
                val pageCtx = ctx.copy(baseUrl = pageUrl)
                LegadoJsEngine.evalListRule(html, pageUrl, source.ruleTocList, pageCtx, state).forEach { el ->
                    val title = LegadoJsEngine.evalTextRule(el, pageUrl, source.ruleTocTitle.ifBlank { "@text" }, pageCtx, state)
                    val url = LegadoJsEngine.evalTextRule(el, pageUrl, source.ruleTocUrl.ifBlank { "@href" }, pageCtx, state)
                    if (title.isNotBlank() && url.isNotBlank()) {
                        all += ChapterMeta(index = all.size, title = title, url = url)
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
                    val next = BookRuleEngine.fetchPage(nexts[0], ua) ?: break
                    curPage = LegadoJsEngine.runLoginCheck(source, next, ctx, state) {
                        BookRuleEngine.fetchPage(nexts[0], ua)
                    }
                    continue
                }
                for (u in nexts) {
                    val p = BookRuleEngine.fetchPage(u, ua) ?: continue
                    val checked = LegadoJsEngine.runLoginCheck(source, p, ctx, state) {
                        BookRuleEngine.fetchPage(u, ua)
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

    /** 拉单章正文。v0.198.0：JSON 源按 ruleContentReplace 做正文替换。v0.199.0：JS 源走 LegadoJsEngine（含正文分页）。 */
    suspend fun content(source: BookSource, chapter: ChapterMeta): String {
        if (chapter.url.isBlank()) return ""
        if (!isJsSource(source)) {
            // v0.217.0（F43）：非 JS 链路也要传源自定义 UA——原来只 JS 链路传，
            // CSS 源正文永远走默认 UA，需要自定义 UA 的站直接 403。
            val html = BookRuleEngine.getHtml(chapter.url, uaOf(source)) ?: return ""
            val doc = BookRuleEngine.parseDoc(html, chapter.url)
            val raw = if (source.ruleContent.isBlank()) "" else BookRuleEngine.extractContent(doc, source.ruleContent)
            val out = if (source.ruleContentReplace.isBlank()) raw
            else BookRuleEngine.applyReplaceRegex(raw, source.ruleContentReplace)
            // v0.202.0：规则提空时用通用正文兜底（无 content 规则的源也由此可读）
            return out.ifBlank { readabilityText(chapter.url, html) }
        }
        return withContext(Dispatchers.IO) {
            val ua = uaOf(source)
            val state = LegadoJsEngine.JsState(source.key)
            val ctx = LegadoJsEngine.JsCtx(
                source = source,
                baseUrl = chapter.url,
                chapterTitle = chapter.title,
                chapterUrl = chapter.url,
            )
            val first = BookRuleEngine.fetchPage(chapter.url, ua) ?: return@withContext ""
            val checked = LegadoJsEngine.runLoginCheck(source, first, ctx, state) {
                BookRuleEngine.fetchPage(chapter.url, ua)
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
                    val p = BookRuleEngine.fetchPage(nexts[0], ua) ?: break
                    val c = LegadoJsEngine.runLoginCheck(source, p, ctx, state) {
                        BookRuleEngine.fetchPage(nexts[0], ua)
                    }
                    curBody = c.body
                    curUrl = c.url
                    continue
                }
                for (u in nexts) {
                    val p = BookRuleEngine.fetchPage(u, ua) ?: continue
                    val c = LegadoJsEngine.runLoginCheck(source, p, ctx, state) {
                        BookRuleEngine.fetchPage(u, ua)
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
                                LegadoJsEngine.jsLibPrelude(source),
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
            outText.ifBlank { readabilityText(chapter.url, checked.body) }
        }
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
                LegadoJsEngine.jsLibPrelude(source),
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
        if (source.ruleBookCover.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val page = BookRuleEngine.fetchPage(book.remoteId, uaOf(source)) ?: return@withContext null
            val ctx = LegadoJsEngine.JsCtx(
                source = source,
                baseUrl = page.url,
                bookName = book.title,
                bookUrl = book.remoteId,
                bookAuthor = book.author.orEmpty(),
            )
            val state = LegadoJsEngine.JsState(source.key)
            val checked = LegadoJsEngine.runLoginCheck(source, page, ctx, state) {
                BookRuleEngine.fetchPage(book.remoteId, uaOf(source))
            }
            val doc = BookRuleEngine.parseDoc(checked.body, checked.url)
            LegadoJsEngine.evalTextRule(doc, checked.url, source.ruleBookCover, ctx.copy(baseUrl = checked.url), state)
                .ifBlank { null }
        }
    }

    /** v0.229.0：搜书详情页用——详情页规则一次求全（书名/作者/简介/封面/类型），
     * 空规则返回空串，调用方回落搜索结果字段。走 detailCover 同款抓取链：
     * fetchPage + runLoginCheck（refetch 必传，227 的教训：999 握手后不重抓
     * 拿到的是“success”短串）；非 JS 源走纯 CSS 提取。 */
    data class BookDetailInfo(
        val name: String = "",
        val author: String = "",
        val intro: String = "",
        val cover: String = "",
        val kind: String = "",
    )

    suspend fun bookDetail(source: BookSource, book: NovelSearchResult): BookDetailInfo {
        if (book.remoteId.isBlank()) return BookDetailInfo()
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!isJsSource(source)) {
                    val html = BookRuleEngine.getHtml(book.remoteId) ?: return@runCatching BookDetailInfo()
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
                    val page = BookRuleEngine.fetchPage(book.remoteId, uaOf(source))
                        ?: return@runCatching BookDetailInfo()
                    val ctx = LegadoJsEngine.JsCtx(
                        source = source,
                        baseUrl = page.url,
                        bookName = book.title,
                        bookUrl = book.remoteId,
                        bookAuthor = book.author.orEmpty(),
                    )
                    val state = LegadoJsEngine.JsState(source.key)
                    val checked = LegadoJsEngine.runLoginCheck(source, page, ctx, state) {
                        BookRuleEngine.fetchPage(book.remoteId, uaOf(source))
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
            }.onFailure { LogBus.w(TAG, "详情抓取失败 ${book.remoteId}: ${it.message}") }
                .getOrDefault(BookDetailInfo())
        }
    }

    /**
     * v0.201.0：发现页——按分类 URL 抓一页，返回书目。
     * 规则取 ruleExplore，缺失项回落到 ruleSearch（源只有 exploreUrl 没写
     * ruleExplore 时也能用）；分类 URL 支持 {{page}} 模板与相对地址（按源地址补全）。
     */
    suspend fun explore(source: BookSource, categoryUrl: String, page: Int, allowGate: Boolean = false): List<NovelSearchResult> {
        if (source.exploreUrl.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val url = resolveUrl(source.url, categoryUrl.replace("{{page}}", page.toString()))
            val ua = uaOf(source)
            val first = gatedFetch(source, ua, url, allowGate) {
                BookRuleEngine.fetchPageOutcome(url, ua)
            } ?: return@withContext emptyList()
            val state = LegadoJsEngine.JsState(source.key)
            val ctx = LegadoJsEngine.JsCtx(source = source, baseUrl = first.url, page = page)
            val checked = LegadoJsEngine.runLoginCheck(source, first, ctx, state) {
                BookRuleEngine.fetchPage(url, ua)
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
                    remoteId = bookUrl,
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
            .onFailure { LogBus.w(TAG, "PY 书源搜索失败 ${rule.name}: ${it.message}") }
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
            .onFailure { LogBus.w(TAG, "PY 书源目录失败 ${book.sourceKey}: ${it.message}") }
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
            .onFailure { LogBus.w(TAG, "PY 书源正文失败 $sourceKey: ${it.message}") }
            .getOrDefault("")
    }
}
