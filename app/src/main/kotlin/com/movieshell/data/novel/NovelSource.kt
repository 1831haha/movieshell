package com.movieshell.data.novel

/**
 * v0.37.0 在线小说源接口。演示源用 mock 数据打通链路，真实网页解析下版再做。
 */
interface NovelSource {
    val key: String
    val name: String
    suspend fun search(keyword: String): List<NovelSearchResult>
    suspend fun chapters(novel: NovelSearchResult): List<ChapterMeta>
    suspend fun content(chapter: ChapterMeta): String
}

/** 搜索结果（轻量，不含正文）。 */
data class NovelSearchResult(
    val sourceKey: String = "",
    val remoteId: String = "",
    val title: String = "",
    val author: String? = null,
    val cover: String? = null,
)

/** 章节元信息（目录用）。 */
data class ChapterMeta(
    val index: Int = 0,
    val title: String = "",
    val url: String = "",
)
