package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.NovelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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
    ): String {
        val (js, _) = LegadoJsEngine.splitJsPrefix(source.searchUrl)
        if (js == null) return source.searchUrl
        val out = LegadoJsEngine.evalScript(
            js,
            LegadoJsEngine.bindings(ctx, state, result = "", src = ""),
        )
        return LegadoJsEngine.toJsString(out).ifBlank { source.searchUrl }
    }

    /** 在单个书源里搜书。v0.197.0：搜索走 POST 感知的请求（老 GET 源行为不变）。v0.199.0：JS 源走 LegadoJsEngine。 */
    suspend fun search(source: BookSource, keyword: String): List<NovelSearchResult> {
        if (source.searchUrl.isBlank() || source.ruleSearchList.isBlank()) return emptyList()
        if (!isJsSource(source)) {
            val req = BookRuleEngine.buildSearchRequest(source.searchUrl, keyword)
            val html = BookRuleEngine.fetchSearchHtml(req) ?: return emptyList()
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
            val urlStr = evalSearchUrl(source, ctx, state)
            val req = BookRuleEngine.buildSearchRequest(urlStr, keyword)
            var page = BookRuleEngine.fetchSearchPage(req, ua) ?: return@withContext emptyList()
            page = LegadoJsEngine.runLoginCheck(source, page, ctx, state, refetch = {
                val urlStr2 = evalSearchUrl(source, ctx, state)
                val req2 = BookRuleEngine.buildSearchRequest(urlStr2, keyword)
                BookRuleEngine.fetchSearchPage(req2, ua)
            })
            val pageCtx = ctx.copy(baseUrl = page.url)
            val items = LegadoJsEngine.evalListRule(page.body, page.url, source.ruleSearchList, pageCtx, state)
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
    ): List<Pair<BookSource, List<NovelSearchResult>>> = coroutineScope {
        sources.map { s ->
            async {
                s to searchSemaphore.withPermit {
                    runCatching { search(s, keyword) }
                        .onFailure { Log.w(TAG, "搜索失败 ${s.name}", it) }
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
            val ctx = LegadoJsEngine.JsCtx(source = source, baseUrl = detailUrl)
            var tocUrl = detailUrl
            if (source.ruleBookTocUrl.isNotBlank()) {
                val detailPage = BookRuleEngine.fetchPage(detailUrl, ua) ?: return@withContext emptyList()
                val checked = LegadoJsEngine.runLoginCheck(source, detailPage, ctx, state, null)
                val doc = BookRuleEngine.parseDoc(checked.body, checked.url)
                val resolved = LegadoJsEngine.evalTextRule(doc, checked.url, source.ruleBookTocUrl, ctx, state)
                if (resolved.isNotBlank()) tocUrl = resolved
            }
            var curPage = BookRuleEngine.fetchPage(tocUrl, ua) ?: return@withContext emptyList()
            curPage = LegadoJsEngine.runLoginCheck(source, curPage, ctx, state, null)
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
                    curPage = LegadoJsEngine.runLoginCheck(source, next, ctx, state, null)
                    continue
                }
                for (u in nexts) {
                    val p = BookRuleEngine.fetchPage(u, ua) ?: continue
                    val checked = LegadoJsEngine.runLoginCheck(source, p, ctx, state, null)
                    collect(checked.body, checked.url)
                }
                break
            }
            all.filterNot { NovelStore.isNavChapterTitle(it.title) }
                .mapIndexed { i, m -> m.copy(index = i) }
        }
    }

    /** 拉单章正文。v0.198.0：JSON 源按 ruleContentReplace 做正文替换。v0.199.0：JS 源走 LegadoJsEngine（含正文分页）。 */
    suspend fun content(source: BookSource, chapter: ChapterMeta): String {
        if (chapter.url.isBlank() || source.ruleContent.isBlank()) return ""
        if (!isJsSource(source)) {
            val html = BookRuleEngine.getHtml(chapter.url) ?: return ""
            val doc = BookRuleEngine.parseDoc(html, chapter.url)
            val raw = BookRuleEngine.extractContent(doc, source.ruleContent)
            return if (source.ruleContentReplace.isBlank()) raw
            else BookRuleEngine.applyReplaceRegex(raw, source.ruleContentReplace)
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
            val checked = LegadoJsEngine.runLoginCheck(source, first, ctx, state, null)
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
                    val c = LegadoJsEngine.runLoginCheck(source, p, ctx, state, null)
                    curBody = c.body
                    curUrl = c.url
                    continue
                }
                for (u in nexts) {
                    val p = BookRuleEngine.fetchPage(u, ua) ?: continue
                    val c = LegadoJsEngine.runLoginCheck(source, p, ctx, state, null)
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
                                js,
                                LegadoJsEngine.bindings(ctx, state, result = outText, src = outText),
                            ),
                        )
                    } else {
                        outText
                    }
                } else {
                    BookRuleEngine.applyReplaceRegex(outText, source.ruleContentReplace)
                }
            }
            outText
        }
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
                js,
                LegadoJsEngine.bindings(ctx.copy(baseUrl = pageUrl), state, result = "", src = html),
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
            .onFailure { Log.w(TAG, "PY 书源搜索失败 ${rule.name}", it) }
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
            .onFailure { Log.w(TAG, "PY 书源目录失败 ${book.sourceKey}", it) }
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
            .onFailure { Log.w(TAG, "PY 书源正文失败 $sourceKey", it) }
            .getOrDefault("")
    }
}
