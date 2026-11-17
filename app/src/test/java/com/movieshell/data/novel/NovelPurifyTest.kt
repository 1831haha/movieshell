package com.movieshell.data.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** v1.2.28：正文里字面量 \r（反斜杠+r，站方双重转义）不能原样上屏——黑岩 #破冰实证。 */
class NovelPurifyTest {

    @Test fun literalBackslashRBecomesParagraphBreak() {
        // 字面量 \r（Kotlin 源码里写 \\r）：每段末一个，段中没有。
        val raw = "第一段\\r第二段\\r第三段"
        val out = NovelPurifyEngine.purify(raw, emptyList())
        assertFalse(out.contains("\\r"))
        assertEquals("　　第一段\n\n　　第二段\n\n　　第三段", out)
    }

    @Test fun literalBackslashNAndRealCrAreNormalizedToo() {
        val raw = "第一段\\n第二段\r\n第三段\r第四段"
        val out = NovelPurifyEngine.purify(raw, emptyList())
        assertFalse(out.contains("\\n"))
        assertFalse(out.contains("\r"))
        assertEquals("　　第一段\n\n　　第二段\n\n　　第三段\n\n　　第四段", out)
    }

    @Test fun plainTextKeepsParagraphFormat() {
        val raw = "第一段\n第二段"
        assertEquals("　　第一段\n\n　　第二段", NovelPurifyEngine.purify(raw, emptyList()))
    }
}
