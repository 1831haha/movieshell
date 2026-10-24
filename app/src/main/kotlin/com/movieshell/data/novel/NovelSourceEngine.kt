package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.NovelStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    /** 在单个书源里搜书。 */
    suspend fun search(source: BookSource, keyword: String): List<NovelSearchResult> {
        if (source.searchUrl.isBlank() || source.ruleSearchList.isBlank()) return emptyList()
        val url = BookRuleEngine.buildSearchUrl(source.searchUrl, keyword)
        val html = BookRuleEngine.getHtml(url) ?: return emptyList()
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

    /** 拉单章正文。 */
    suspend fun content(source: BookSource, chapter: ChapterMeta): String {
        if (chapter.url.isBlank() || source.ruleContent.isBlank()) return ""
        val html = BookRuleEngine.getHtml(chapter.url) ?: return ""
        val doc = BookRuleEngine.parseDoc(html, chapter.url)
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
