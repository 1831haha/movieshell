package com.movieshell.data.novel

import com.movieshell.core.js.LegadoJsEngine
import com.movieshell.domain.model.BookSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.2.23：JSON 搜索（黑岩阅读这类 `$.data.content[*]` API 源）。
 * 响应样本取自 2026-11-17 沙箱实抓的 search.heiyan.com 返回结构。
 */
class JsonSearchTest {

    private val heiyanJson = """
        {"success":true,"code":1,"data":{"content":[
        {"id":143131,"name":"我的三个姐姐","authorname":"<span>佚名</span>","introduce":"入狱三年，回来复仇。","iconUrlSmall":"//img.heiyan.com/1.jpg@!bns?0"},
        {"id":143132,"name":"我的同桌","authorname":"阿七","introduce":"青春故事。","iconUrlSmall":"//img.heiyan.com/2.jpg"}
        ]}}
    """.trimIndent()

    private fun ctx(): LegadoJsEngine.JsCtx {
        val src = BookSource(name = "黑岩", url = "https://www.heiyan.com")
        return LegadoJsEngine.JsCtx(src.toJsRuleSource(), "https://search.heiyan.com/")
    }

    private fun state() = LegadoJsEngine.JsState("www.heiyan.com")

    @Test fun jsonPathListStarExpandsArray() {
        val root = LegadoJsEngine.parseJson(heiyanJson)!!
        val items = LegadoJsEngine.jsonPathQuery(root, "$.data.content[*]")
        assertEquals(2, items.size)
        assertEquals(143131, (items[0] as JSONObject).getInt("id"))
    }

    @Test fun jsonPathIndexAndField() {
        val root = LegadoJsEngine.parseJson(heiyanJson)!!
        assertEquals("我的三个姐姐", LegadoJsEngine.jsonPathString(root, "$.data.content[0].name"))
        assertEquals("阿七", LegadoJsEngine.jsonPathString(root, "$.data.content[1].authorname"))
    }

    @Test fun jsonPathMissingReturnsEmpty() {
        val root = LegadoJsEngine.parseJson(heiyanJson)!!
        assertEquals(0, LegadoJsEngine.jsonPathQuery(root, "$.data.nothing[*]").size)
        assertEquals("", LegadoJsEngine.jsonPathString(root, "$.data.content[9].name"))
    }

    @Test fun evalJsonListRuleExpandsBareArray() {
        // 没写 [*]、路径直接指到数组时同样展开
        val items = LegadoJsEngine.evalJsonListRule(heiyanJson, "$.data.content", ctx(), state())
        assertEquals(2, items.size)
    }

    @Test fun evalJsonFieldPlainPath() {
        val root = LegadoJsEngine.parseJson(heiyanJson)!!
        val item = LegadoJsEngine.jsonPathQuery(root, "$.data.content[0]").first()
        assertEquals("我的三个姐姐", LegadoJsEngine.evalJsonField(item, "$.name", ctx(), state()))
        assertEquals("入狱三年，回来复仇。", LegadoJsEngine.evalJsonField(item, "$.introduce", ctx(), state()))
    }

    @Test fun evalJsonFieldJsConcat() {
        val root = LegadoJsEngine.parseJson(heiyanJson)!!
        val item = LegadoJsEngine.jsonPathQuery(root, "$.data.content[0]").first()
        assertEquals(
            "https://www.heiyan.com/book/143131",
            LegadoJsEngine.evalJsonField(item, "$.id@js:'https://www.heiyan.com/book/'+result", ctx(), state()),
        )
    }

    @Test fun evalJsonFieldRegexStrip() {
        val root = LegadoJsEngine.parseJson(heiyanJson)!!
        val item = LegadoJsEngine.jsonPathQuery(root, "$.data.content[0]").first()
        assertEquals("佚名", LegadoJsEngine.evalJsonField(item, "$.authorname##<.*?>", ctx(), state()))
    }

    // v1.2.24：`||` 备选——第一个取到值的分支胜出。
    @Test fun evalJsonFieldOrFallback() {
        val root = LegadoJsEngine.parseJson(heiyanJson)!!
        val item = LegadoJsEngine.jsonPathQuery(root, "$.data.content[0]").first()
        assertEquals("我的三个姐姐", LegadoJsEngine.evalJsonField(item, "$.nickname || $.name", ctx(), state()))
        assertEquals("", LegadoJsEngine.evalJsonField(item, "$.nickname || $.alias", ctx(), state()))
    }

    @Test fun evalJsonListRuleOrFallback() {
        val items = LegadoJsEngine.evalJsonListRule(
            heiyanJson, "$.data.nothing || $.data.content[*]", ctx(), state(),
        )
        assertEquals(2, items.size)
        val items2 = LegadoJsEngine.evalJsonListRule(
            heiyanJson, "$.data.content[*] || $.data.nothing", ctx(), state(),
        )
        assertEquals(2, items2.size)
    }

    // v1.2.24：`{$.x}` 内嵌——一处字段的值嵌进另一处 @js: 拼接。
    @Test fun evalJsonFieldInterpolatesOtherField() {
        val root = LegadoJsEngine.parseJson(heiyanJson)!!
        val item = LegadoJsEngine.jsonPathQuery(root, "$.data.content[0]").first()
        assertEquals(
            "https://x.com/我的三个姐姐/143131",
            LegadoJsEngine.evalJsonField(
                item,
                "$.id@js:'https://x.com/' + '{$.name}' + '/' + result",
                ctx(), state(),
            ),
        )
    }
}
