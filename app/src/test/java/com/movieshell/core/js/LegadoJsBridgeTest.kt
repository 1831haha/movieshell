package com.movieshell.core.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.2.25：JS 桥补的阅读式方法（随心看/七猫/123读书的 searchUrl 在用，之前缺函数整条求值失败）。 */
class LegadoJsBridgeTest {

    private fun bridge(): LegadoJsEngine.JavaBridge =
        LegadoJsEngine.JavaBridge(LegadoJsEngine.JsState("test-source"), null)

    @Test fun putReturnsStoredValueAndGetReadsIt() {
        val b = bridge()
        // 七猫：put 的返回值拼进 searchUrl 的 ,{...} 后缀。
        assertEquals("{\"headers\":{}}", b.put("headers", "{\"headers\":{}}"))
        assertEquals("{\"headers\":{}}", b.get("headers"))
        assertEquals("", b.get("missing"))
    }

    @Test fun putOverwrites() {
        val b = bridge()
        b.put("key", "a")
        b.put("key", "b")
        assertEquals("b", b.get("key"))
    }

    @Test fun encodeUriGbk() {
        val b = bridge()
        // “测试” GBK 编码：%B2%E2%CA%D4。
        assertEquals("%B2%E2%CA%D4", b.encodeURI("测试", "GBK"))
        assertEquals("abc", b.encodeURI("abc", "GBK"))
        assertEquals("abc", b.encodeURI("abc", "NO_SUCH_CHARSET"))
    }

    @Test fun timeFormatSecondsEqualsMillis() {
        val b = bridge()
        // 秒/毫秒自判：同一时刻两种写法输出一致，且格式为 yyyy-MM-dd HH:mm（时区走设备默认，不 assert 绝对时间）。
        val fromSeconds = b.timeFormat(1767225600L)
        val fromMillis = b.timeFormat(1767225600000L)
        assertEquals(fromSeconds, fromMillis)
        assertTrue(fromSeconds.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
        assertEquals(fromSeconds, b.timeFormat("1767225600"))
        assertEquals("not-a-time", b.timeFormat("not-a-time"))
        assertEquals("", b.timeFormat(null))
    }

    @Test fun sourceBridgeExposesCommentAndKey() {
        val src = JsRuleSource(key = "k", bookSourceComment = "function f(){return 1}")
        val bridge = LegadoJsEngine.SourceBridge(src, LegadoJsEngine.JsState("k"))
        assertEquals("k", bridge.getKey())
        assertEquals("function f(){return 1}", bridge.getBookSourceComment())
    }
}
