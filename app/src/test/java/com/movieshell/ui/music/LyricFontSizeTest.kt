package com.movieshell.ui.music

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricFontSizeTest {

    @Test fun scaleOneKeepsOriginalSize() {
        val sized = scaledLyricFontSize(TextStyle(fontSize = 16.sp), 1.0f)
        assertEquals(16f, sized.fontSize.value, 0.001f)
    }

    @Test fun scaleMultipliesSize() {
        // 大 1.15：16 → 18.4；小 0.85：16 → 13.6；特大 1.3：16 → 20.8。
        assertEquals(18.4f, scaledLyricFontSize(TextStyle(fontSize = 16.sp), 1.15f).fontSize.value, 0.01f)
        assertEquals(13.6f, scaledLyricFontSize(TextStyle(fontSize = 16.sp), 0.85f).fontSize.value, 0.01f)
        assertEquals(20.8f, scaledLyricFontSize(TextStyle(fontSize = 16.sp), 1.3f).fontSize.value, 0.01f)
    }
}
