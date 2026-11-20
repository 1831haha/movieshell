package com.movieshell.player

import androidx.media3.common.C
import com.movieshell.player.AdFilteringDataSource.Companion.LengthDecision
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.3.33 B-P1-1：去广告长度决策与有界读取（纯函数，不发网络、不碰上游）。
 */
class AdFilteringDataSourceTest {

    @Test
    fun decideFilter_unknownLengthFilters() {
        assertEquals(LengthDecision.FILTER, AdFilteringDataSource.decideFilter(C.LENGTH_UNSET.toLong()))
    }

    @Test
    fun decideFilter_smallKnownLengthFilters() {
        assertEquals(LengthDecision.FILTER, AdFilteringDataSource.decideFilter(1L))
        assertEquals(LengthDecision.FILTER, AdFilteringDataSource.decideFilter(8L * 1024 * 1024))
    }

    @Test
    fun decideFilter_zeroOrNegativePassthrough() {
        assertEquals(LengthDecision.PASSTHROUGH, AdFilteringDataSource.decideFilter(0L))
        assertEquals(LengthDecision.PASSTHROUGH, AdFilteringDataSource.decideFilter(-2L))
    }

    @Test
    fun decideFilter_over8mbPassthrough() {
        assertEquals(LengthDecision.PASSTHROUGH, AdFilteringDataSource.decideFilter(8L * 1024 * 1024 + 1))
        // 3,000,000,000 的 toInt() 是负数（会 NegativeArraySizeException）
        assertEquals(LengthDecision.PASSTHROUGH, AdFilteringDataSource.decideFilter(3_000_000_000L))
        // 5,000,000,000 的 toInt() 约 705MB（一次分配易 OOM）
        assertEquals(LengthDecision.PASSTHROUGH, AdFilteringDataSource.decideFilter(5_000_000_000L))
    }

    /** 按字节数组供数据的 chunk 函数（读完回 EOF）。 */
    private fun chunkOf(data: ByteArray): Pair<(ByteArray, Int, Int) -> Int, () -> Int> {
        var pos = 0
        val fn: (ByteArray, Int, Int) -> Int = { buf, off, len ->
            if (pos >= data.size) C.RESULT_END_OF_INPUT
            else {
                val n = minOf(len, data.size - pos)
                data.copyInto(buf, off, pos, pos + n)
                pos += n
                n
            }
        }
        return fn to { pos }
    }

    @Test
    fun readCapped_finiteDataReturnsAll() {
        val data = "hello playlist".toByteArray(Charsets.UTF_8)
        val (fn) = chunkOf(data)
        assertArrayEquals(data, AdFilteringDataSource.readCapped(1024, fn))
    }

    @Test
    fun readCapped_emptyReturnsEmpty() {
        val (fn) = chunkOf(ByteArray(0))
        assertEquals(0, AdFilteringDataSource.readCapped(1024, fn).size)
    }

    @Test
    fun readCapped_exactLimitOk() {
        val data = ByteArray(100) { it.toByte() }
        val (fn) = chunkOf(data)
        assertEquals(100, AdFilteringDataSource.readCapped(100, fn).size)
    }

    @Test(expected = IOException::class)
    fun readCapped_oneByteOverLimitThrows() {
        val data = ByteArray(101) { it.toByte() }
        val (fn) = chunkOf(data)
        AdFilteringDataSource.readCapped(100, fn)
    }

    @Test(expected = IOException::class)
    fun readCapped_endlessStreamThrows() {
        // 每次都回满缓冲、永不 EOF：无限流必须在超限时停下，不能读到 OOM。
        AdFilteringDataSource.readCapped(100) { _, _, len -> len }
    }
}
