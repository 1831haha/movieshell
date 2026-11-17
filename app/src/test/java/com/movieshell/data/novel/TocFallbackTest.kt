package com.movieshell.data.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.2.25：目录兜底——详情页/目录页上找“目录”链接（黑岩实证：详情页上的“查看章节目录”链到真目录页）。 */
class TocFallbackTest {

    private val detailHtml = """
        <html><body>
        <a href="/book/84149/2230495">第一章 测试</a>
        <a href="https://w2.heiyan.com/chapter/84149">查看章节目录</a>
        </body></html>
    """.trimIndent()

    @Test fun findsCatalogLink() {
        assertEquals(
            "https://w2.heiyan.com/chapter/84149",
            BookRuleEngine.findTocLink(detailHtml, "https://www.heiyan.com/book/84149"),
        )
    }

    @Test fun resolvesRelativeCatalogLink() {
        val html = """<html><body><a href="/chapter/1">目录</a></body></html>"""
        assertEquals(
            "https://x.com/chapter/1",
            BookRuleEngine.findTocLink(html, "https://x.com/book/1"),
        )
    }

    @Test fun ignoresSamePageLink() {
        val html = """<html><body><a href="#toc">目录</a></body></html>"""
        assertNull(BookRuleEngine.findTocLink(html, "https://x.com/book/1"))
    }

    @Test fun returnsNullWithoutCatalogLink() {
        val html = """<html><body><a href="/book/1/2">第一章</a></body></html>"""
        assertNull(BookRuleEngine.findTocLink(html, "https://x.com/book/1"))
    }
}
