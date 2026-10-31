package com.movieshell.ui.music

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceInfoLineTest {
    @Test fun lines() {
        assertEquals("网易云 · 经 GD Studio", sourceInfoLine("网易云", "GD Studio"))
        assertEquals("网易云", sourceInfoLine("网易云", "网易云"))
        assertEquals("网易云", sourceInfoLine("网易云", ""))
        assertEquals("经 GD Studio", sourceInfoLine(null, "GD Studio"))
        assertEquals("", sourceInfoLine(null, null))
    }
}
