package com.movieshell.domain.source

import com.movieshell.domain.model.BookDetailInfo
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.ContentResult
import com.movieshell.domain.model.NovelSearchResult

/**
 * v1.1.6 分层第 2 批：小说源引擎的领域接口（小说形状，不套视频 SourceEngine——
 * 小说没有首页/分类/播放，链路是搜书 → 目录 → 正文）。
 *
 * 方法名与签名照抄 data/novel/NovelSourceEngine 的真实链路。
 * JSON 书源（BookSource）与 PY 书源（PyBookRule）各一个薄适配器实现本接口，
 * 调用方凭源种类拿引擎，不再到处分 JSON/PY 两支。
 * 多源并发搜（searchAll/searchAllPy）与工具函数仍留在 NovelSourceEngine 上。
 */
interface NovelEngine {

    /** 在单个书源里搜书。 */
    suspend fun search(keyword: String, allowGate: Boolean = false): List<NovelSearchResult>

    /** 拉目录。book.remoteId 为空时返回空列表。 */
    suspend fun chapters(book: NovelSearchResult): List<ChapterMeta>

    /** 拉单章正文（带失败分类，不再以空串抹掉 403/超时/DNS 的区别）。 */
    suspend fun content(chapter: ChapterMeta, allowGate: Boolean = false): ContentResult

    /** 发现页：按分类 URL 抓一页，返回书目。 */
    suspend fun explore(categoryUrl: String, page: Int, allowGate: Boolean = false): List<NovelSearchResult>

    /** 搜书详情页用：详情页规则一次求全（书名/作者/简介/封面/类型）。 */
    suspend fun bookDetail(book: NovelSearchResult): BookDetailInfo

    /** 详情封面求值：仅在搜索结果封面为空时由入架流程调用。 */
    suspend fun detailCover(book: NovelSearchResult): String?
}
