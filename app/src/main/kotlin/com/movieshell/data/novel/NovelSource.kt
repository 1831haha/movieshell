package com.movieshell.data.novel

// v0.109.0 B7：旧 NovelSource 接口已删（全仓零引用，DemoNovelSource 一并删除；
// 真实链路走 BookRuleEngine/PyBookApi）。同文件的 NovelSearchResult/ChapterMeta
// 被多处使用，保留。

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
