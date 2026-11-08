package com.movieshell.data.novel

import android.content.Context
import com.movieshell.domain.model.BookDetailInfo
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.ContentFail
import com.movieshell.domain.model.ContentResult
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.domain.source.NovelEngine

/**
 * v1.1.6 分层第 2 批：PY 书源适配器——把绑规则的 PyBookRule 接到 NovelEngine。
 * 只做委托与结果形态转换，不含逻辑（仿第 1 批 Type4SourceEngine 的绑站做法）。
 * PY 规则没有发现页与详情封面链：explore 回空列表，detailCover 回 null，
 * 与改版前调用方行为一致；bookDetail 走规则的 bookDetail 取名/作者/简介/封面。
 */
class PyNovelEngine(
    private val context: Context,
    private val rule: PyBookSourceManager.PyBookRule,
) : NovelEngine {

    private val sourceKey: String get() = PyBookSourceManager.SOURCE_KEY_PREFIX + rule.name

    override suspend fun search(keyword: String, allowGate: Boolean): List<NovelSearchResult> =
        NovelSourceEngine.searchPy(context, rule, keyword)

    override suspend fun chapters(book: NovelSearchResult): List<ChapterMeta> =
        NovelSourceEngine.chaptersPy(context, book)

    override suspend fun content(chapter: ChapterMeta, allowGate: Boolean): ContentResult {
        val text = NovelSourceEngine.contentPy(context, chapter, sourceKey)
        // PY 侧失败不分类（规则文件缺失/规则报错/回空都混在一起），按调用方
        // 原行为（ReaderScreen）定 UNKNOWN，不谎称 PARSE_EMPTY。
        return if (text.isBlank()) ContentResult.Failed(ContentFail.UNKNOWN)
        else ContentResult.Ok(text)
    }

    override suspend fun explore(categoryUrl: String, page: Int, allowGate: Boolean): List<NovelSearchResult> =
        emptyList()

    override suspend fun bookDetail(book: NovelSearchResult): BookDetailInfo =
        NovelSourceEngine.bookDetailPy(context, book)

    override suspend fun detailCover(book: NovelSearchResult): String? = null
}
