package com.movieshell.data.store

import com.movieshell.data.novel.ParagraphCommentStore
import com.movieshell.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.14：删书清缓存的键范围（纯函数）——只认这本书的文件，不碰 index.json 与别的书；
 * 清缓存正文时只把 content 置空，目录、标题、下标、所属书一律不动。
 */
class NovelCacheScopeTest {

    @Test fun cacheFileNamesAreExactlyThisBook() {
        assertEquals(
            listOf("bookA.json", "bookA.json.bak", "bookA.journal", "bookA.comments.json"),
            NovelStore.bookCacheFileNames("bookA"),
        )
    }

    @Test fun cacheFileNamesNeverTouchIndexOrOtherBooks() {
        val names = NovelStore.bookCacheFileNames("bookA")
        assertTrue(names.none { it == "index.json" })
        assertTrue(names.all { it.startsWith("bookA.") })
        assertTrue(names.none { it.contains("bookB") })
        assertTrue(NovelStore.bookCacheFileNames("bookB").none { it in names })
    }

    @Test fun commentCacheFileNameIsPerBook() {
        assertEquals("bookA.comments.json", ParagraphCommentStore.cacheFileName("bookA"))
        assertTrue(ParagraphCommentStore.cacheFileName("bookA") != ParagraphCommentStore.cacheFileName("bookB"))
    }

    @Test fun stripCachedContentsBlanksOnlyContent() {
        val chapters = listOf(
            Chapter(novelId = "bookA", index = 0, title = "第一章", content = "正文一", url = "http://x/1"),
            Chapter(novelId = "bookA", index = 1, title = "第二章", content = "", url = "http://x/2"),
        )
        val stripped = NovelStore.stripCachedContents(chapters)
        assertEquals(listOf("", ""), stripped.map { it.content })
        assertEquals(listOf("第一章", "第二章"), stripped.map { it.title })
        assertEquals(listOf(0, 1), stripped.map { it.index })
        assertEquals(listOf("bookA", "bookA"), stripped.map { it.novelId })
        assertEquals(listOf("http://x/1", "http://x/2"), stripped.map { it.url })
    }

    @Test fun stripCachedContentsEmptyStaysEmpty() {
        assertEquals(emptyList<Chapter>(), NovelStore.stripCachedContents(emptyList()))
    }
}
