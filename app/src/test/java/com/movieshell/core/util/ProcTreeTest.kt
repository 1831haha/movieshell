package com.movieshell.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.3.28：ProcTree 纯函数回归测试（不碰 Android）。 */
class ProcTreeTest {

    @Test
    fun parseChildrenFile() {
        assertEquals(listOf(12, 34, 56), parseProcChildren("12 34 56"))
        assertEquals(listOf(12, 34), parseProcChildren("12 34\n"))
        assertEquals(emptyList<Int>(), parseProcChildren(""))
        assertEquals(emptyList<Int>(), parseProcChildren("  \n"))
        assertEquals(listOf(7), parseProcChildren("7"))
    }

    @Test
    fun collectDescendantsInPreorder() {
        val children = mapOf(
            100 to listOf(101, 102),
            101 to listOf(103),
            102 to emptyList(),
            103 to emptyList(),
        )
        assertEquals(
            listOf(101, 103, 102),
            collectProcDescendants(100) { children[it].orEmpty() },
        )
    }

    @Test
    fun collectDescendantsStopsOnCycle() {
        val children = mapOf(1 to listOf(2), 2 to listOf(1))
        assertEquals(listOf(2, 1), collectProcDescendants(1) { children[it].orEmpty() })
    }

    @Test
    fun collectDescendantsEmptyWhenLeaf() {
        assertEquals(emptyList<Int>(), collectProcDescendants(5) { emptyList() })
    }
}
