package com.movieshell.core.js

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.33：jsLib URL 识别（jsLibPrelude 是同步函数，不能在里面拉网，URL 形式必须在保存时明确拒绝）。 */
class LegadoJsLibTest {

    @Test
    fun isJsLibUrl_detectsHttpHttps() {
        assertTrue(LegadoJsEngine.isJsLibUrl("https://example.com/lib.js"))
        assertTrue(LegadoJsEngine.isJsLibUrl("http://example.com/lib.js"))
        assertTrue(LegadoJsEngine.isJsLibUrl("  https://example.com/lib.js"))
        assertTrue(LegadoJsEngine.isJsLibUrl("HTTPS://example.com/lib.js"))
    }

    @Test
    fun isJsLibUrl_rejectsInlineAndLookalikes() {
        assertFalse(LegadoJsEngine.isJsLibUrl(""))
        assertFalse(LegadoJsEngine.isJsLibUrl("function helper() { return 1; }"))
        assertFalse(LegadoJsEngine.isJsLibUrl("var http = 1;"))
        assertFalse(LegadoJsEngine.isJsLibUrl("httpfoo://example.com"))
        assertFalse(LegadoJsEngine.isJsLibUrl("ftp://example.com/lib.js"))
    }
}
