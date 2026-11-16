package com.movieshell.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.14：小说向前预载窗口（纯函数）——只往后、不往前、不含当前章；越界截断。
 */
class ReaderPreloadTest {

    @Test fun constantIsTen() {
        assertEquals(10, PRELOAD_AHEAD_COUNT)
    }

    @Test fun forwardTenFromStart() {
        assertEquals((1..10).toList(), preloadIndices(0, 100))
    }

    @Test fun forwardTenFromMiddle() {
        assertEquals((6..15).toList(), preloadIndices(5, 100))
    }

    @Test fun neverIncludesCurrentOrBackward() {
        val window = preloadIndices(50, 100)
        assertTrue(50 !in window)
        assertTrue(window.none { it < 50 })
    }

    @Test fun truncatedAtBookEnd() {
        assertEquals((96..99).toList(), preloadIndices(95, 100))
    }

    @Test fun lastChapterHasEmptyWindow() {
        assertEquals(emptyList<Int>(), preloadIndices(99, 100))
    }

    @Test fun smallBookTruncated() {
        assertEquals(listOf(1, 2), preloadIndices(0, 3))
    }

    @Test fun emptyBookHasEmptyWindow() {
        assertEquals(emptyList<Int>(), preloadIndices(0, 0))
    }

    @Test fun customAheadRespected() {
        assertEquals(listOf(1, 2), preloadIndices(0, 100, ahead = 2))
        assertEquals(emptyList<Int>(), preloadIndices(0, 100, ahead = 0))
    }
}
