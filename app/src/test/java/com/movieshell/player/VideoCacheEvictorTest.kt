package com.movieshell.player

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.File
import java.util.NavigableSet
import java.util.TreeSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VideoCacheEvictor 单测：用假 Cache 验两条线——超期清扫与 LRU 尺寸驱逐。
 * 假缓存只实现驱逐器用到的行为；removeSpan 时像 SimpleCache 一样回通知
 * onSpanRemoved，让 LRU 内部的已用字节账与真实现一致。
 */
class VideoCacheEvictorTest {

    private val dayMs = 24L * 3600 * 1000

    private fun span(key: String, position: Long, length: Long, lastTouch: Long): CacheSpan =
        CacheSpan(key, position, length, lastTouch, File("seg"))

    private class FakeCache : Cache {
        val spans = mutableMapOf<String, TreeSet<CacheSpan>>()
        val removed = mutableListOf<CacheSpan>()
        var evictor: CacheEvictor? = null

        fun addSpan(span: CacheSpan) {
            spans.getOrPut(span.key) { TreeSet() }.add(span)
        }

        fun contains(span: CacheSpan): Boolean = spans[span.key]?.contains(span) == true

        override fun getUid(): Long = 0
        override fun release() {}
        override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> =
            getCachedSpans(key)
        override fun removeListener(key: String, listener: Cache.Listener) {}
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> =
            TreeSet(spans[key] ?: emptySet())
        override fun getKeys(): Set<String> = spans.keys.toSet()
        override fun getCacheSpace(): Long = spans.values.sumOf { s -> s.sumOf { it.length } }
        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan =
            throw UnsupportedOperationException()
        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan =
            throw UnsupportedOperationException()
        override fun startFile(key: String, position: Long, length: Long): File =
            throw UnsupportedOperationException()
        override fun commitFile(file: File, length: Long) {}
        override fun releaseHoleSpan(holeSpan: CacheSpan) {}
        override fun removeResource(key: String) {
            spans.remove(key)
        }
        override fun removeSpan(span: CacheSpan) {
            spans[span.key]?.remove(span)
            removed += span
            evictor?.onSpanRemoved(this, span)
        }
        override fun isCached(key: String, position: Long, length: Long): Boolean = false
        override fun getCachedLength(key: String, position: Long, length: Long): Long = 0
        override fun getCachedBytes(key: String, position: Long, length: Long): Long = 0
        override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {}
        override fun getContentMetadata(key: String): ContentMetadata = DefaultContentMetadata()
    }

    /** 模拟 SimpleCache 建缓存时逐分片调 onSpanAdded 的顺序。 */
    private fun feed(fake: FakeCache, evictor: VideoCacheEvictor, span: CacheSpan) {
        fake.addSpan(span)
        evictor.onSpanAdded(fake, span)
    }

    @Test fun `loading stale spans evicts them and keeps fresh ones`() {
        val now = System.currentTimeMillis()
        val fake = FakeCache()
        val evictor = VideoCacheEvictor(10L * 1024 * 1024, 30 * dayMs)
        fake.evictor = evictor
        val stale = span("a", 0, 100, now - 40 * dayMs)
        val fresh = span("b", 0, 100, now - dayMs)
        feed(fake, evictor, stale)
        feed(fake, evictor, fresh)
        assertFalse(fake.contains(stale))
        assertTrue(fake.contains(fresh))
        assertEquals(listOf(stale), fake.removed)
    }

    @Test fun `init callback sweeps stale spans`() {
        val now = System.currentTimeMillis()
        val fake = FakeCache()
        val evictor = VideoCacheEvictor(10L * 1024 * 1024, 30 * dayMs)
        fake.evictor = evictor
        val stale = span("a", 0, 100, now - 40 * dayMs)
        val fresh = span("b", 0, 100, now - dayMs)
        fake.addSpan(stale)
        fake.addSpan(fresh)
        // 只用 onStartFile 把缓存引用递给驱逐器（不触发清扫），再调初始化回调；
        // 本用例只验清扫结果，不验之后的尺寸驱逐。
        evictor.onStartFile(fake, "b", 0L, C.LENGTH_UNSET)
        evictor.onCacheInitialized()
        assertFalse(fake.contains(stale))
        assertTrue(fake.contains(fresh))
        assertEquals(listOf(stale), fake.removed)
    }

    @Test fun `unlimited age never evicts by age`() {
        val now = System.currentTimeMillis()
        val fake = FakeCache()
        val evictor = VideoCacheEvictor(10L * 1024 * 1024, null)
        fake.evictor = evictor
        val ancient = span("a", 0, 100, now - 400 * dayMs)
        feed(fake, evictor, ancient)
        evictor.onCacheInitialized()
        assertTrue(fake.contains(ancient))
        assertTrue(fake.removed.isEmpty())
    }

    @Test fun `size eviction still evicts least recently used first`() {
        val now = System.currentTimeMillis()
        val fake = FakeCache()
        val evictor = VideoCacheEvictor(1000, 30 * dayMs)
        fake.evictor = evictor
        val older = span("a", 0, 600, now - 2 * dayMs)
        val newer = span("b", 0, 600, now - dayMs)
        feed(fake, evictor, older)
        feed(fake, evictor, newer)
        assertFalse(fake.contains(older))
        assertTrue(fake.contains(newer))
        assertEquals(listOf(older), fake.removed)
    }

    @Test fun `stale span is removed even when cache is under the size cap`() {
        val now = System.currentTimeMillis()
        val fake = FakeCache()
        val evictor = VideoCacheEvictor(10L * 1024 * 1024, 7 * dayMs)
        fake.evictor = evictor
        val stale = span("a", 0, 100, now - 8 * dayMs)
        val fresh = span("b", 0, 100, now - 6 * dayMs)
        feed(fake, evictor, stale)
        feed(fake, evictor, fresh)
        assertFalse(fake.contains(stale))
        assertTrue(fake.contains(fresh))
    }
}
