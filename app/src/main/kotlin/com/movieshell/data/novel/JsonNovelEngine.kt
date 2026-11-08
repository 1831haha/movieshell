package com.movieshell.data.novel

import android.content.Context
import com.movieshell.domain.model.BookDetailInfo
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.ContentResult
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.domain.model.ParagraphComment
import com.movieshell.domain.source.NovelEngine

/**
 * v1.1.6 分层第 2 批：JSON 书源适配器——把绑站的 BookSource 接到 NovelEngine。
 * 只做委托，不含逻辑（仿第 1 批 Type4SourceEngine 的绑站做法）。
 * v1.1.10：构造带 Context——段评 sidecar 缓存要落 filesDir（与 PyNovelEngine 同款）。
 */
class JsonNovelEngine(private val context: Context, private val source: BookSource) : NovelEngine {

    override suspend fun search(keyword: String, allowGate: Boolean): List<NovelSearchResult> =
        NovelSourceEngine.search(source, keyword, allowGate)

    override suspend fun chapters(book: NovelSearchResult): List<ChapterMeta> =
        NovelSourceEngine.chapters(source, book)

    override suspend fun content(chapter: ChapterMeta, allowGate: Boolean): ContentResult =
        NovelSourceEngine.content(source, chapter, allowGate)

    override suspend fun explore(categoryUrl: String, page: Int, allowGate: Boolean): List<NovelSearchResult> =
        NovelSourceEngine.explore(source, categoryUrl, page, allowGate)

    override suspend fun bookDetail(book: NovelSearchResult): BookDetailInfo =
        NovelSourceEngine.bookDetail(source, book)

    override suspend fun detailCover(book: NovelSearchResult): String? =
        NovelSourceEngine.detailCover(source, book)

    override suspend fun paragraphComments(
        novelId: String,
        chapter: ChapterMeta,
        allowGate: Boolean,
    ): List<ParagraphComment> =
        NovelSourceEngine.paragraphComments(context, source, novelId, chapter, allowGate)
}
