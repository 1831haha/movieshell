package com.movieshell.core.js

import com.movieshell.data.novel.BookRuleEngine
import com.movieshell.data.novel.toJsRuleSource
import com.movieshell.domain.model.BookSource
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.2.26：XPath 选段转 CSS（引擎此前只会 Jsoup CSS，// 开头的规则一律选空，黑岩目录全灭）。 */
class XpathToCssTest {

    @Test fun simpleChildPath() {
        assertEquals("li > a", LegadoJsEngine.xpathToCss("//li/a"))
    }

    @Test fun hasChildPredicate() {
        assertEquals("li:has(span) > a", LegadoJsEngine.xpathToCss("//li[span]/a"))
    }

    @Test fun textEqualsPredicate() {
        assertEquals("a:containsOwn(开始阅读)", LegadoJsEngine.xpathToCss("//a[text()=\"开始阅读\"]"))
    }

    @Test fun containsTextPredicate() {
        assertEquals("a:contains(目录)", LegadoJsEngine.xpathToCss("//a[contains(text(),\"目录\")]"))
    }

    @Test fun attributePredicate() {
        assertEquals("[class=\"read\"]", LegadoJsEngine.xpathToCss("//*[@class=\"read\"]"))
        assertEquals(
            "select[name=\"titleselect\"] > option",
            LegadoJsEngine.xpathToCss("//select[@name=\"titleselect\"]/option"),
        )
    }

    @Test fun indexAndPositionPredicates() {
        assertEquals("ul[class=\"chapter\"]:nth-of-type(2) > li > a", LegadoJsEngine.xpathToCss("//ul[@class='chapter'][2]/li/a"))
        assertEquals(
            "div[class=\"directoryArea\"] > p:nth-of-type(n+6) > a",
            LegadoJsEngine.xpathToCss("//div[@class=\"directoryArea\"]/p[position()>5]/a"),
        )
    }

    @Test fun followingSiblingAxis() {
        assertEquals(
            "[id=\"list\"] dt:nth-of-type(2) ~ dd > a",
            LegadoJsEngine.xpathToCss("//*[@id=\"list\"]//dt[2]/following-sibling::dd/a"),
        )
    }

    @Test fun unsupportedReturnsNull() {
        assertNull(LegadoJsEngine.xpathToCss("//div[@id=\"list\"]/dl/dt[last()]/following-sibling::dd/a"))
        assertNull(LegadoJsEngine.xpathToCss("//dl[@id=\"dir\"]/*<js>var a=1</js>a"))
        assertNull(LegadoJsEngine.xpathToCss("div.foo"))
        assertNull(LegadoJsEngine.xpathToCss("//a[text()=\"下一页\"]/../following-sibling::li/a"))
    }

    private val catalogHtml = """
        <html><body><ul>
        <li><a href="/book/143131/11804588">第一章 熟悉又陌生的城市跟人</a></li>
        <li><span class="vip"></span><a href="/book/143131/11804591">第二章再见李晗姐</a></li>
        <li><a href="/book/143131/11804594">第三章弟弟跟姐姐们合租吗</a></li>
        </ul></body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
        <a href="/book/143131/11804588">开始阅读</a>
        <a href="https://w2.heiyan.com/chapter/143131">查看章节目录</a>
        </body></html>
    """.trimIndent()

    private fun ctx(): Pair<LegadoJsEngine.JsCtx, LegadoJsEngine.JsState> {
        val src = BookSource(
            name = "test",
            url = "https://www.heiyan.com",
        )
        return LegadoJsEngine.JsCtx(src.toJsRuleSource(), "https://www.heiyan.com/book/143131") to
            LegadoJsEngine.JsState(src.key)
    }

    @Test fun xpathListRuleSelectsChapters() {
        val (c, s) = ctx()
        val items = LegadoJsEngine.evalListRule(catalogHtml, "https://w2.heiyan.com/chapter/143131", "//li/a", c, s)
        assertEquals(3, items.size)
        assertEquals("第一章 熟悉又陌生的城市跟人", items[0].text())
    }

    @Test fun xpathHasPredicateSelectsOnlySpanned() {
        val (c, s) = ctx()
        val items = LegadoJsEngine.evalListRule(catalogHtml, "https://w2.heiyan.com/chapter/143131", "//li[span]/a", c, s)
        assertEquals(1, items.size)
        assertEquals("第二章再见李晗姐", items[0].text())
    }

    @Test fun xpathTocUrlResolvesHref() {
        val (c, s) = ctx()
        val doc = BookRuleEngine.parseDoc(detailHtml, "https://www.heiyan.com/book/143131")
        assertEquals(
            "https://www.heiyan.com/book/143131/11804588",
            LegadoJsEngine.evalTextRule(doc, "https://www.heiyan.com/book/143131", "//a[text()=\"开始阅读\"]/@href", c, s),
        )
        assertEquals(
            "https://w2.heiyan.com/chapter/143131",
            LegadoJsEngine.evalTextRule(doc, "https://www.heiyan.com/book/143131", "//a[contains(text(),\"目录\")]/@href", c, s),
        )
    }

    @Test fun extractHandlesXpathSelector() {
        val doc = BookRuleEngine.parseDoc(detailHtml, "https://www.heiyan.com/book/143131")
        assertEquals(
            "https://www.heiyan.com/book/143131/11804588",
            BookRuleEngine.extract(doc, "https://www.heiyan.com/book/143131", "//a[text()=\"开始阅读\"]/@href"),
        )
    }

    @Test fun followingSiblingRuleSelectsChaptersAfterSecondVolume() {
        val html = """
            <html><body><div id="list"><dl>
            <dt>第一卷</dt>
            <dd><a href="/c/1">第一章</a></dd>
            <dd><a href="/c/2">第二章</a></dd>
            <dt>第二卷</dt>
            <dd><a href="/c/3">第三章</a></dd>
            <dd><a href="/c/4">第四章</a></dd>
            </dl></div></body></html>
        """.trimIndent()
        val (c, s) = ctx()
        val items = LegadoJsEngine.evalListRule(
            html,
            "https://x.com/book/1",
            "//*[@id=\"list\"]//dt[2]/following-sibling::dd/a",
            c,
            s,
        )
        assertEquals(2, items.size)
        assertEquals("第三章", items[0].text())
        assertEquals("第四章", items[1].text())
    }

    @Test fun inlineJsSegmentPassesAttrValueToJs() {
        val el = Jsoup.parse(
            "<a href=\"/book/143131/11804588\">第一章</a>",
            "https://www.heiyan.com/book/143131",
        ).selectFirst("a")!!
        val out = LegadoJsEngine.evalChain(
            el,
            "https://www.heiyan.com/book/143131",
            "href<js>'https://a.heiyan.com/ajax/chapter/content/'+result.match(/\\d+\$/)[0]</js>",
        ) { _, input -> "AJAX:$input" }.firstText()
        assertEquals("AJAX:https://www.heiyan.com/book/143131/11804588", out)
    }
}
