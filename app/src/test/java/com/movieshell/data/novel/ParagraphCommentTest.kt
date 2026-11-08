package com.movieshell.data.novel

import com.movieshell.domain.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.1.10：段评结构化版——规则字段往返与评论 JSON 解析。 */
class ParagraphCommentTest {

    private val sourceJson = """{"bookSourceName":"晋江文学城","bookSourceUrl":"https://www.jjwxc.net","enabled":true,"searchUrl":"https://www.jjwxc.net/search.php?keyword={{key}}","ruleSearch":{"bookList":".x","name":"@text","bookUrl":"a@href"},"ruleBookInfo":{"name":"h1@text"},"ruleToc":{"chapterList":".c a","chapterName":"@text","chapterUrl":"@href"},"ruleContent":{"content":"#paragraph_comment_content","paragraphComments":"<js>JSON.stringify([])</js>"}}"""

    @Test fun ruleParagraphCommentsRoundTrip() {
        val src = BookSource.fromJson(sourceJson)
        assertNotNull(src)
        assertEquals("<js>JSON.stringify([])</js>", src!!.ruleParagraphComments)
        val back = BookSource.fromJson(src.toJson().toString())
        assertNotNull(back)
        assertEquals("<js>JSON.stringify([])</js>", back!!.ruleParagraphComments)
    }

    @Test fun ruleParagraphCommentsDefaultsEmpty() {
        val src = BookSource.fromJson("""{"bookSourceName":"X","bookSourceUrl":"https://x.example"}""")
        assertNotNull(src)
        assertEquals("", src!!.ruleParagraphComments)
    }

    @Test fun parseRealJinjiangShape() {
        val json = """[{"paraIndex":1,"author":"叙弦纪百转","text":"一刷呀","likes":0,"time":"2026-11-08 01:07:29","total":1375},{"paraIndex":8,"author":"沐槿徊月","text":"酥梨嘿嘿","likes":"1","time":"2026-11-02 23:37:54","total":"120"}]"""
        val list = ParagraphCommentStore.parseJson(json)
        assertNotNull(list)
        assertEquals(2, list!!.size)
        assertEquals(1, list[0].paraIndex)
        assertEquals("叙弦纪百转", list[0].author)
        assertEquals("一刷呀", list[0].text)
        assertEquals(1375, list[0].total)
        assertEquals(8, list[1].paraIndex)
        assertEquals(1, list[1].likes)
        assertEquals(120, list[1].total)
    }

    @Test fun parseDropsPseudoParagraph() {
        val json = """[{"paraIndex":-2,"author":"x","text":"y","likes":0,"time":""},{"paraIndex":3,"author":"a","text":"b","likes":0,"time":""}]"""
        val list = ParagraphCommentStore.parseJson(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertEquals(3, list[0].paraIndex)
    }

    @Test fun parseEmptyArrayIsCacheableEmpty() {
        val list = ParagraphCommentStore.parseJson("[]")
        assertNotNull(list)
        assertTrue(list!!.isEmpty())
    }

    @Test fun parseInvalidReturnsNull() {
        assertNull(ParagraphCommentStore.parseJson("{not json"))
        assertNull(ParagraphCommentStore.parseJson("[{\"paraIndex\":1"))
        assertNull(ParagraphCommentStore.parseJson(""))
        assertNull(ParagraphCommentStore.parseJson("   "))
    }
}
