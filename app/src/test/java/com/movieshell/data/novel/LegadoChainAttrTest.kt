package com.movieshell.data.novel

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** v0.294.55：阅友小说原书源（@onclick@js:）在搜索链里应能取到 bookUrl。 */
class LegadoChainAttrTest {
    private val sourceJson = "{\"bookSourceGroup\":\"\u5c0f\u8bf4\",\"bookSourceName\":\"\u9605\u53cb\u5c0f\u8bf4\",\"bookSourceUrl\":\"https://m.suixkan.com\",\"enabled\":true,\"ruleBookInfo\":{\"author\":\".face-info span.0@text\",\"coverUrl\":\".face-cover img@src\",\"intro\":\"#intro@text\",\"kind\":\"\",\"name\":\".face-info-title@text\",\"tocUrl\":\".sumchapter a@href\"},\"ruleContent\":{\"content\":\".con\",\"replaceRegex\":\"[\\\\(\uff08]\u672c\u7ae0\u672a\u5b8c.*[\uff09\\\\)]|[\\\\(\uff08]\u672c\u7ae0\u5b8c[\uff09\\\\)]\"},\"ruleSearch\":{\"author\":\".v-author@text\",\"bookList\":\".v-list-item\",\"bookUrl\":\"@onclick@js:result.match(/\\\\('(.*?)', '', ''\\\\)/)[1]\",\"coverUrl\":\"img@src\",\"name\":\".v-title@text\"},\"ruleToc\":{\"chapterList\":\".catalog_ls li a\",\"chapterName\":\"@text\",\"chapterUrl\":\"@href\"},\"searchUrl\":\"https://m.suixkan.com/s/1.html?keyword={{key}}&page={{page}}\"}"

    private val item = """<div class="v-list-item flex" onclick="newWebView('/b/235209.html', '', '')">
        <img class="v-cover-img" src="https://cdn.example/c.jpg"/>
        <div class="v-title">我的傲娇美女上司</div><div class="v-author">二哥</div></div>"""

    private fun run(rule: String): String {
        val src = BookSource.fromJson(sourceJson)
        assertNotNull(src)
        val el = Jsoup.parse(item, "https://m.suixkan.com/").selectFirst(".v-list-item")!!
        val ctx = LegadoJsEngine.JsCtx(src!!, "https://m.suixkan.com/s/1.html")
        return LegadoJsEngine.evalTextRule(el, "https://m.suixkan.com/s/1.html", rule, ctx, LegadoJsEngine.JsState(src.key))
    }

    @Test fun onclickAttrIsReadAsAttribute() {
        assertEquals("newWebView('/b/235209.html', '', '')", run("@onclick"))
    }

    @Test fun userSourceBookUrlRuleWorks() {
        val src = BookSource.fromJson(sourceJson)!!
        assertEquals("/b/235209.html", run(src.ruleSearchBookUrl))
        assertEquals("我的傲娇美女上司", run(src.ruleSearchName))
        assertEquals("二哥", run(src.ruleSearchAuthor))
    }

    @Test fun cssClassSelectorStillSelects() {
        assertEquals("二哥", run(".v-author@text"))
    }
}
