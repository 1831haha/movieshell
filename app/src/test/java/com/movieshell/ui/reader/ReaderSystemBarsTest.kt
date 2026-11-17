package com.movieshell.ui.reader

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSystemBarsTest {

    @Test fun bgColorsMatchLegacyMapping() {
        // 与 ReaderScreen 原 when 块逐项一致：0 白、1 米黄（默认）、2 护眼绿、
        // 3 夜间黑、4 跟随系统、5 浅蓝、6 米灰、7 深灰、8 粉、9 紫、10 墨绿。
        assertEquals(Color(0xFFFFFFFF), ReaderSystemBars.readerBgColor(0, false))
        assertEquals(Color(0xFFF7F3E8), ReaderSystemBars.readerBgColor(1, false))
        assertEquals(Color(0xFFE8F0E4), ReaderSystemBars.readerBgColor(2, false))
        assertEquals(Color(0xFF121212), ReaderSystemBars.readerBgColor(3, false))
        assertEquals(Color(0xFFFFFFFF), ReaderSystemBars.readerBgColor(4, false))
        assertEquals(Color(0xFF121212), ReaderSystemBars.readerBgColor(4, true))
        assertEquals(Color(0xFFDCE8EF), ReaderSystemBars.readerBgColor(5, false))
        assertEquals(Color(0xFFD8D4CA), ReaderSystemBars.readerBgColor(6, false))
        assertEquals(Color(0xFF20242B), ReaderSystemBars.readerBgColor(7, false))
        assertEquals(Color(0xFFFBE9EC), ReaderSystemBars.readerBgColor(8, false))
        assertEquals(Color(0xFFE9E4F3), ReaderSystemBars.readerBgColor(9, false))
        assertEquals(Color(0xFF1E2B26), ReaderSystemBars.readerBgColor(10, false))
        assertEquals(Color(0xFFF7F3E8), ReaderSystemBars.readerBgColor(99, false))
    }

    @Test fun darkIconsFollowLuminance() {
        // 图 2 阅读暖黄底、护眼绿底 → 深色图标；夜间黑、深灰底 → 浅色图标。
        assertTrue(ReaderSystemBars.useDarkIcons(Color(0xFFF7F3E8)))
        assertTrue(ReaderSystemBars.useDarkIcons(Color(0xFFE8F0E4)))
        assertFalse(ReaderSystemBars.useDarkIcons(Color(0xFF121212)))
        assertFalse(ReaderSystemBars.useDarkIcons(Color(0xFF20242B)))
    }

    @Test fun publishAndClearRoundTrip() {
        ReaderSystemBars.publish(Color(0xFFE8F0E4))
        assertEquals(Color(0xFFE8F0E4), ReaderSystemBars.barColor)
        ReaderSystemBars.clear()
        assertNull(ReaderSystemBars.barColor)
    }
}
