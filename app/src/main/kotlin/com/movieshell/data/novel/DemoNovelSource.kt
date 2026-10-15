package com.movieshell.data.novel

import kotlinx.coroutines.delay

/**
 * v0.37.0 演示小说源：硬编码 mock 数据，只为打通 搜索→目录→正文→加入书架 全链路。
 * 真实网页解析下版再做。
 */
object DemoNovelSource : NovelSource {
    override val key = "demo"
    override val name = "演示书源"

    private val books = listOf(
        Triple("m1", "星海漫游指南", "刘宇航"),
        Triple("m2", "深巷小吃店", "陈晓雨"),
        Triple("m3", "代码里的江湖", "方程式"),
    )

    private fun fakeContent(title: String, chapter: String): String = buildString {
        appendLine("　　$chapter")
        appendLine()
        repeat(6) { p ->
            appendLine("　　这是《$title》的演示正文第 ${p + 1} 段。演示源只提供占位文字，真实书源接入后这里会是实际章节内容。")
            appendLine()
        }
    }

    override suspend fun search(keyword: String): List<NovelSearchResult> {
        delay(400)
        val kw = keyword.trim()
        return books
            .filter { kw.isEmpty() || it.second.contains(kw) || it.third.contains(kw) }
            .map { (id, title, author) ->
                NovelSearchResult(sourceKey = key, remoteId = id, title = title, author = author)
            }
    }

    override suspend fun chapters(novel: NovelSearchResult): List<ChapterMeta> {
        delay(300)
        return (1..5).map { i ->
            ChapterMeta(index = i - 1, title = "第${i}章 演示章节", url = "mock://${novel.remoteId}/$i")
        }
    }

    override suspend fun content(chapter: ChapterMeta): String {
        delay(200)
        val book = books.firstOrNull { "mock://${it.first}/" in chapter.url }?.second ?: "演示小说"
        return fakeContent(book, chapter.title)
    }
}
