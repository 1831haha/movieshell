package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.33 B-P2-5：豆瓣缓存 TTL 与有界淘汰（纯函数，不发网络）。
 */
class DoubanReviewsCacheTest {

    @Test
    fun isExpired_boundary() {
        assertFalse(DoubanReviews.isExpired(1000L, 1500L, 500L))
        assertFalse(DoubanReviews.isExpired(1000L, 1500L, 501L))
        assertTrue(DoubanReviews.isExpired(1000L, 1501L, 500L))
    }

    @Test
    fun oldestKeyIfOver_underCapReturnsNull() {
        val map = mapOf("a" to 100L, "b" to 50L)
        assertNull(DoubanReviews.oldestKeyIfOver(map, 2) { it })
        assertNull(DoubanReviews.oldestKeyIfOver(map, 3) { it })
    }

    @Test
    fun oldestKeyIfOver_overCapReturnsOldest() {
        val map = mapOf("a" to 100L, "b" to 50L, "c" to 200L)
        assertEquals("b", DoubanReviews.oldestKeyIfOver(map, 2) { it })
    }

    @Test
    fun oldestKeyIfOver_emptyMapReturnsNull() {
        assertNull(DoubanReviews.oldestKeyIfOver(emptyMap<String, Long>(), 0) { it })
    }
}
