package com.movieshell.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessExitLogTest {
    @Test fun reasons() {
        assertEquals("原生崩溃", ProcessExitLog.reasonName(5))
        assertEquals("无响应(ANR)", ProcessExitLog.reasonName(6))
    }
    @Test fun printableExtractsStrings() {
        val b = byteArrayOf(0, 1) + "signal 11 (SIGSEGV)".toByteArray() + byteArrayOf(0, 9) + "abc".toByteArray() + byteArrayOf(0) + "libtorrent4j.so".toByteArray()
        assertEquals(listOf("signal 11 (SIGSEGV)", "libtorrent4j.so"), ProcessExitLog.printable(b))
    }
}
