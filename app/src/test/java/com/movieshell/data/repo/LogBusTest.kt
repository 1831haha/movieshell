package com.movieshell.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** LogBus 环形缓冲语义测试（v0.7.2）。 */
class LogBusTest {

    @Before
    fun setUp() {
        LogBus.clear()
    }

    @Test
    fun `ring buffer keeps only last MAX entries`() {
        repeat(LogBus.MAX + 5) { i -> LogBus.i("T", "msg-$i") }
        val snap = LogBus.snapshot()
        assertEquals(LogBus.MAX, snap.size)
        assertEquals("msg-5", snap.first().msg)
        assertEquals("msg-${LogBus.MAX + 4}", snap.last().msg)
    }

    @Test
    fun `levels are recorded`() {
        LogBus.d("T", "a")
        LogBus.i("T", "b")
        LogBus.w("T", "c")
        LogBus.e("T", "d")
        val levels = LogBus.snapshot().map { it.level }
        assertEquals(listOf('D', 'I', 'W', 'E'), levels)
    }

    @Test
    fun `dumpText contains entries in order`() {
        LogBus.i("MyTag", "hello")
        LogBus.e("MyTag", "boom")
        val text = LogBus.dumpText()
        assertTrue(text.contains("I/MyTag: hello"))
        assertTrue(text.contains("E/MyTag: boom"))
        assertTrue(text.indexOf("hello") < text.indexOf("boom"))
    }

    @Test
    fun `clear empties buffer`() {
        LogBus.i("T", "x")
        LogBus.clear()
        assertTrue(LogBus.snapshot().isEmpty())
        assertEquals("", LogBus.dumpText())
    }
}
