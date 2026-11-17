package com.movieshell.data.novel

import com.movieshell.core.js.JsRuleSource
import com.movieshell.core.js.LegadoJsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // v1.2.28：通用目录兜底——章节链接是否属于本书（话本实证：裸 li>a 的 70 条全是导航/推荐/页脚）。
    @Test fun sameBookChapterLinkMatchesChapterHref() {
        val book = "https://www.ihuaben.com/book/2105794.html"
        assertEquals(true, BookRuleEngine.isSameBookChapterLink("/book/2105794/49294810.html", book))
        assertEquals(true, BookRuleEngine.isSameBookChapterLink("https://www.ihuaben.com/book/2105794/49294810.html", book))
        assertEquals(true, BookRuleEngine.isSameBookChapterLink("//www.ihuaben.com/book/2105794/49294810.html", book))
    }

    @Test fun sameBookChapterLinkRejectsOtherBooksAndNav() {
        val book = "https://www.ihuaben.com/book/2105794.html"
        assertEquals(false, BookRuleEngine.isSameBookChapterLink("/book/2014645.html", book))
        assertEquals(false, BookRuleEngine.isSameBookChapterLink("/book/2105794/", book))
        assertEquals(false, BookRuleEngine.isSameBookChapterLink("https://www.ihuaben.com/", book))
        assertEquals(false, BookRuleEngine.isSameBookChapterLink("/about", book))
        assertEquals(false, BookRuleEngine.isSameBookChapterLink("", book))
        assertEquals(false, BookRuleEngine.isSameBookChapterLink("/book/2105794/1.html", ""))
    }

    @Test fun sameBookChapterLinkWorksAcrossHosts() {
        // 黑岩：书页在 www，章节在 w2 子站，只认路径。
        assertEquals(
            true,
            BookRuleEngine.isSameBookChapterLink(
                "https://w2.heiyan.com/book/143131/11804588",
                "https://www.heiyan.com/book/143131",
            ),
        )
        assertEquals(
            false,
            BookRuleEngine.isSameBookChapterLink("/book/44778", "https://www.heiyan.com/book/143131"),
        )
    }

    @Test fun genericFallbackKeepsOnlySameBookLinks() {
        // 话本详情页微缩版：导航/推荐/页脚全在 li 里，真章节在 div.chapter-row 里。
        val html = """
            <html><body>
            <ul><li><a href="/">首页</a></li><li><a href="/about">关于我们</a></li></ul>
            <ul><li><a href="/book/2014645.html">对不起，请忘了我吧</a></li>
            <li><a href="/book/3004181.html">爱过，那只是一瞬间</a></li></ul>
            <div class="chapters"><div class="chapter-list">
            <div class="chapter-row"><span class="col-title"><a href="/book/2105794/49294810.html">第一章</a></span></div>
            <div class="chapter-row"><span class="col-title"><a href="/book/2105794/49294811.html">第二章</a></span></div>
            <div class="chapter-row"><span class="col-title"><a href="/book/2105794/49294812.html">第三章</a></span></div>
            </div></div>
            <ul><li><a href="/help">帮助中心</a></li><li><a href="/download">下载客户端</a></li></ul>
            </body></html>
        """.trimIndent()
        val bookUrl = "https://www.ihuaben.com/book/2105794.html"
        val doc = BookRuleEngine.parseDoc(html, bookUrl)
        // 旧口径裸 li>a：7 条全是错的。
        assertEquals(7, doc.select("li > a").size)
        // 新口径 a[href] + 同书过滤：只剩 3 章。
        val kept = doc.select("a[href]").filter {
            BookRuleEngine.isSameBookChapterLink(it.absUrl("href"), bookUrl)
        }
        assertEquals(3, kept.size)
        assertEquals("第一章", kept[0].text())
        assertEquals("第三章", kept[2].text())
    }

    @Test fun jsFallbackSelectorFindsChapterLinks() {
        // JS 路径的兜底用 evalListRule("a[href]")——裸 CSS 选择器必须走通链式求值。
        val html = """
            <html><body>
            <ul><li><a href="/">首页</a></li></ul>
            <div class="chapter-row"><a href="/book/2105794/49294810.html">第一章</a></div>
            <div class="chapter-row"><a href="/book/2105794/49294811.html">第二章</a></div>
            <div class="chapter-row"><a href="/book/2105794/49294812.html">第三章</a></div>
            </body></html>
        """.trimIndent()
        val bookUrl = "https://www.ihuaben.com/book/2105794.html"
        val ctx = LegadoJsEngine.JsCtx(source = JsRuleSource(key = "k"), baseUrl = bookUrl)
        val state = LegadoJsEngine.JsState("k")
        val els = LegadoJsEngine.evalListRule(html, bookUrl, "a[href]", ctx, state)
        assertTrue(els.size >= 4)
        val kept = els.filter {
            BookRuleEngine.isSameBookChapterLink(it.absUrl("href"), bookUrl)
        }
        assertEquals(3, kept.size)
        assertEquals("第一章", kept[0].text())
    }
}
