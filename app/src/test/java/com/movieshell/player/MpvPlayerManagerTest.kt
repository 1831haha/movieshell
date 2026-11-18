package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvPlayerManagerTest {

    @Test
    fun mpvLogTailText_blankReturnsEmpty() {
        assertEquals("", mpvLogTailText(""))
        assertEquals("", mpvLogTailText("   \n  \n"))
    }

    @Test
    fun mpvLogTailText_takesLastLines() {
        val text = (1..50).joinToString("\n") { "line$it" }
        assertEquals("line48\nline49\nline50", mpvLogTailText(text, maxLines = 3))
    }

    @Test
    fun mpvLogTailText_dropsBlankLines() {
        assertEquals("a\nb", mpvLogTailText("a\n\nb\n\n", maxLines = 10))
    }

    @Test
    fun mpvLogTailText_truncatesToMaxChars() {
        val text = "x".repeat(100)
        assertEquals("x".repeat(10), mpvLogTailText(text, maxLines = 5, maxChars = 10))
    }
}
