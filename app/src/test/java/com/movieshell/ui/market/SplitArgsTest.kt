package com.movieshell.ui.market

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.3.28：splitArgs 回归测试（纯函数，不碰 Android）。
 * 此前 doSave 按空白切分，带空格的路径参数（引号内空格）会被拆错。
 */
class SplitArgsTest {

    @Test
    fun plainSplitOnWhitespace() {
        assertEquals(
            listOf("--path", "/sdcard/x", "--flag"),
            splitArgs("--path /sdcard/x --flag"),
        )
    }

    @Test
    fun doubleQuotedSegmentKeepsSpaces() {
        assertEquals(
            listOf("--path", "/sdcard/My Dir/x"),
            splitArgs("--path \"/sdcard/My Dir/x\""),
        )
    }

    @Test
    fun singleQuotedSegmentKeepsSpaces() {
        assertEquals(
            listOf("--path", "/sdcard/My Dir/x"),
            splitArgs("--path '/sdcard/My Dir/x'"),
        )
    }

    @Test
    fun emptyQuotedStringKeepsEmptyArg() {
        assertEquals(listOf("a", "", "b"), splitArgs("a \"\" b"))
    }

    @Test
    fun unclosedQuoteTreatedAsPlainChars() {
        assertEquals(listOf("a", "b c"), splitArgs("a \"b c"))
    }

    @Test
    fun blankInputGivesEmptyList() {
        assertEquals(emptyList<String>(), splitArgs("   "))
    }
}
