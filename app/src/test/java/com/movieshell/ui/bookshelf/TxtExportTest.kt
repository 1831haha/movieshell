package com.movieshell.ui.bookshelf

import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.Novel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.33：单本 TXT 导出拼文本（书名 + 作者 + 每章标题与正文）。 */
class TxtExportTest {

    @Test
    fun buildTxtExport_fullText() {
        val novel = Novel(id = "n1", title = "测试小说", author = "测试作者")
        val chapters = listOf(
            Chapter(novelId = "n1", index = 0, title = "第一章 开始", content = "这是第一章正文。"),
            Chapter(novelId = "n1", index = 1, title = "第二章 继续", content = "  第二章正文前后有空格。  "),
            Chapter(novelId = "n1", index = 2, title = "", content = ""),
        )
        val text = buildTxtExport(novel, chapters)
        val expected = "测试小说\n" +
            "作者：测试作者\n" +
            "\n第一章 开始\n这是第一章正文。\n" +
            "\n第二章 继续\n第二章正文前后有空格。\n" +
            "\n未命名章节\n"
        assertEquals(expected, text)
    }

    @Test
    fun buildTxtExport_noAuthorNoChapters() {
        val novel = Novel(id = "n2", title = "无名书")
        val text = buildTxtExport(novel, emptyList())
        assertEquals("无名书\n", text)
        assertTrue(!text.contains("作者"))
    }
}
