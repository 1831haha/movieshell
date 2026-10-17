package com.movieshell.data.novel

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * v0.61.0 书源执行引擎：把 BookSource 规则跑通
 * 搜索 → 书籍详情 → 目录 → 章节正文。
 *
 * 所有网络 IO 走 BookRuleEngine；失败吞掉打日志，返回空。
 */
object NovelSourceEngine {

    private const val TAG = "NovelSourceEngine"

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

    /** 多源并发搜，结果按源分组返回。 */
    suspend fun searchAll(
        sources: List<BookSource>,
        keyword: String,
    ): List<Pair<BookSource, List<NovelSearchResult>>> = coroutineScope {
        sources.map { s ->
            async {
                s to runCatching { search(s, keyword) }
                    .onFailure { Log.w(TAG, "搜索失败 ${s.name}", it) }
                    .getOrDefault(emptyList())
            }
        }.awaitAll()
    }

    /** 拉目录。bookUrl 为空时尝试直接用 remoteId。 */
    suspend fun chapters(source: BookSource, book: NovelSearchResult): List<ChapterMeta> {
        val tocUrl = book.remoteId.ifBlank { return emptyList() }
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
    }

    /** 拉单章正文。 */
    suspend fun content(source: BookSource, chapter: ChapterMeta): String {
        if (chapter.url.isBlank() || source.ruleContent.isBlank()) return ""
        val html = BookRuleEngine.getHtml(chapter.url) ?: return ""
        val doc = BookRuleEngine.parseDoc(html, chapter.url)
        return BookRuleEngine.extractContent(doc, source.ruleContent)
    }
}
