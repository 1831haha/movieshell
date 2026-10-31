package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SniffAlternatesTest {
    @Test fun `next walks group then exhausts`() {
        SniffAlternates.clear()
        SniffAlternates.register(listOf("u1", "u2", "u3", "u4").map { SniffAlternates.Candidate("https://h/$it.m3u8", emptyMap()) })
        assertEquals("https://h/u2.m3u8", SniffAlternates.next("https://h/u1.m3u8")!!.url)
        assertEquals("https://h/u3.m3u8", SniffAlternates.next("https://h/u2.m3u8")!!.url)
        assertNull(SniffAlternates.next("https://h/u3.m3u8"))
        assertNull(SniffAlternates.next("https://h/u4.m3u8")) // 只保留前 3 个
    }

    @Test fun `single candidate not registered`() {
        SniffAlternates.clear()
        SniffAlternates.register(listOf(SniffAlternates.Candidate("https://h/only.m3u8", emptyMap())))
        assertNull(SniffAlternates.next("https://h/only.m3u8"))
    }
}
