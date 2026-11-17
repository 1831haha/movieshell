package com.movieshell.data.novel

import com.movieshell.core.js.LegadoJsEngine
import com.movieshell.domain.model.BookSource
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.28：JSON 路径正文规则（对标阅读 AnalyzeRule.getString）。
 *
 * 根因（黑岩 #破冰实证，2026-11-17 沙箱字节级核对）：
 * 章节接口回的是 JSON，`htmlContent` 里的 `\r` 是标准单层转义；
 * 规则 `$.chapter.htmlContent@js:...` 不是 CSS 选择器，旧链路走
 * extractContent（Jsoup 对 `$` 开头选择器抛错返回空串），再回退
 * readabilityText 对原始 JSON 文本提字，转义以字面量漏上屏。
 * 修法：按 JSON 路径求值（解码一次），再跑 @js: 后缀，取出的 HTML
 * 片段按块级分段。
 *
 * 样本：2026-11-17 沙箱实抓 a.heiyan.com/ajax/chapter/content/9499274
 * （《我的万能商店》第二章）前 5 段逐字原文，信封字段齐全。
 */
class JsonContentRuleTest {

    private val heiyanChapterJson = """
        {"chapter":{"additional":"","showTime":"2019-03-11 13:52","book":{"iconUrlMedium":"/book/fakeImage21.jpg@!bm?1","id":122021,"title":"我的万能商店"},"words":2937,"id":9499274,"free":true,"title":"第二章：商店开启","writerVO":{"name":"格林与安徒生丶","staff":false,"id":20357747,"writer":true,"title":"格林与安徒生丶","url":"/people/20357747"},"htmlContent":"<p>“什么，一瓶破可乐，你卖两百？”\r</p><p>“是的，客人，我这牌子的可乐不但健康无污染，还有减肥瘦身的功效，很便宜！”\r</p><p>“你看我像傻子吗？喝可乐能减肥？骗鬼呢，真是想钱想疯了，自己留着喝吧！”\r</p><p>一名年轻人愤怒的走出杂货店。\r</p><p>苏铭无奈，快乐水的神奇功效，若非亲眼所见，的确有点难以想象。\r</p>"}}
    """.trimIndent()

    private val rule = "$.chapter.htmlContent@js:result?result:'付费章节，换源阅读'"

    private fun ctx(): LegadoJsEngine.JsCtx {
        val src = BookSource(name = "黑岩", url = "https://www.heiyan.com")
        return LegadoJsEngine.JsCtx(
            src.toJsRuleSource(),
            "https://a.heiyan.com/ajax/chapter/content/9499274",
        )
    }

    private fun state() = LegadoJsEngine.JsState("a.heiyan.com")

    @Test fun splitJsonRuleSplitsPathAndJs() {
        val split = LegadoJsEngine.splitJsonRule(rule)!!
        assertEquals("$.chapter.htmlContent", split.first)
        assertEquals("result?result:'付费章节，换源阅读'", split.second)
        val bare = LegadoJsEngine.splitJsonRule("$.a.b[0].c")!!
        assertEquals("$.a.b[0].c", bare.first)
        assertNull(bare.second)
        assertNull(LegadoJsEngine.splitJsonRule("div.content"))
        assertNull(LegadoJsEngine.splitJsonRule("<js>result</js>"))
    }

    @Test fun evalJsonRuleDecodesRealChapterWithoutLiteralR() {
        // JSON 解码一次：\r 转义还原成真实回车（0x0D），不是字面量。
        val decoded = LegadoJsEngine.evalJsonRule(heiyanChapterJson, rule, ctx(), state())!!
        assertTrue(decoded.contains("“什么，一瓶破可乐，你卖两百？”"))
        assertTrue(decoded.contains("\r"))
        assertFalse(decoded.contains("\\r"))
        // HTML 片段按块级分段：5 段干净正文，无任何 \r。
        val text = BookRuleEngine.extractHtmlText(decoded, "https://a.heiyan.com/ajax/chapter/content/9499274")
        assertFalse(text.contains("\r"))
        assertFalse(text.contains("\\r"))
        assertEquals(
            "　　“什么，一瓶破可乐，你卖两百？”\n\n" +
                "　　“是的，客人，我这牌子的可乐不但健康无污染，还有减肥瘦身的功效，很便宜！”\n\n" +
                "　　“你看我像傻子吗？喝可乐能减肥？骗鬼呢，真是想钱想疯了，自己留着喝吧！”\n\n" +
                "　　一名年轻人愤怒的走出杂货店。\n\n" +
                "　　苏铭无奈，快乐水的神奇功效，若非亲眼所见，的确有点难以想象。",
            text,
        )
    }

    @Test fun oldPathReturnsEmptyAndReadabilityLeaksLiteralR() {
        // 旧链路定格：extractContent 对 $. 规则直接返回空串……
        val doc = Jsoup.parse(heiyanChapterJson, "https://a.heiyan.com/ajax/chapter/content/9499274")
        assertEquals("", BookRuleEngine.extractContent(doc, rule))
        // ……content() 再回退 readabilityText 对原始 JSON 文本提字，
        // \r 转义原样进了屏幕（用户 1.2.27 截图里的字面量 \r 就是这么来的）。
        val leaked = net.dankito.readability4j.Readability4J(
            "https://a.heiyan.com/ajax/chapter/content/9499274",
            heiyanChapterJson,
        ).parse().textContent.orEmpty()
        assertTrue(leaked.isNotBlank())
        assertTrue(leaked.contains("\\r"))
    }

    @Test fun evalJsonPathRuleBarePathNeedsNoJs() {
        // 不带 @js: 的裸 $. 规则走非 JS 链路：同样解码，不碰 Rhino。
        val decoded = LegadoJsEngine.evalJsonPathRule(heiyanChapterJson, "$.chapter.htmlContent")!!
        assertTrue(decoded.contains("\r"))
        assertFalse(decoded.contains("\\r"))
        assertNull(LegadoJsEngine.evalJsonPathRule(heiyanChapterJson, "div.content"))
        // 带 @js: 的必须走带 ctx 的 evalJsonRule，这里返回 null 不吞规则。
        assertNull(LegadoJsEngine.evalJsonPathRule(heiyanChapterJson, rule))
        assertNull(LegadoJsEngine.evalJsonPathRule(heiyanChapterJson, "$.chapter.{{field}}"))
    }

    @Test fun extractHtmlTextSplitsParagraphsAndTrimsRealCr() {
        val text = BookRuleEngine.extractHtmlText(
            "<p>第一段\r</p><p>第二段\r</p>",
            "https://a.heiyan.com/",
        )
        assertEquals("　　第一段\n\n　　第二段", text)
        assertEquals("", BookRuleEngine.extractHtmlText("", "https://a.heiyan.com/"))
    }

    @Test fun evalJsonRuleMissingPathRunsJsFallback() {
        // 路径取不到时 @js: 的兜底生效（付费章节文案），不是空串也不是报错。
        assertEquals(
            "付费章节",
            LegadoJsEngine.evalJsonRule(heiyanChapterJson, "$.chapter.nope@js:result?result:'付费章节'", ctx(), state()),
        )
        assertEquals("", LegadoJsEngine.evalJsonRule(heiyanChapterJson, "$.chapter.nope", ctx(), state()))
    }
}
