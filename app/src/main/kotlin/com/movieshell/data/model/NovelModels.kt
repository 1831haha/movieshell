package com.movieshell.data.model

import kotlinx.serialization.Serializable

/**
 * v0.35.0 小说：一本书的元信息。章节正文存在 novels/{id}.json，不进这个表。
 */
@Serializable
data class Novel(
    val id: String = "",
    val sourceKey: String = "local", // local=TXT 导入，后续在线源用源 key
    val title: String = "",
    val author: String? = null,
    val cover: String? = null,
    val desc: String? = null,
    val totalChapters: Int = 0,
    val lastReadChapter: Int = 0, // 已读到的章节下标（从 0 起），0=没读过
    val addedAt: Long = 0L,
)

/** 单章。正文 content 可能很长，单独存文件。 */
@Serializable
data class Chapter(
    val novelId: String = "",
    val index: Int = 0,
    val title: String = "",
    val content: String = "",
)

/** 阅读进度：读到第几章、章内第几个字符。 */
@Serializable
data class ReadingProgress(
    val novelId: String = "",
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val updatedAt: Long = 0L,
)

/** 书架分组。 */
enum class ShelfTab { READING, WANT, FINISHED }
