package com.movieshell.data.repo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.294.1：换源串台根因回归——中文规则名不能再撞到同一个可信副本/缓存文件。 */
class SafeFileNameTest {

    @Test
    fun chineseRuleNamesGetDistinctTrustedPaths() {
        val a = ExecutableTrust.trustedName("py:红果短剧.py")
        val b = ExecutableTrust.trustedName("py:西瓜影视.py")
        val c = ExecutableTrust.trustedName("py:央视.py")
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(b, c)
        // 保留 .py 后缀（Python 按后缀选 loader）
        listOf(a, b, c).forEach { assertTrue(it, it.endsWith(".py")) }
        // 稳定：同 key 同名
        assertEquals(a, ExecutableTrust.trustedName("py:红果短剧.py"))
        // 无路径逃逸
        listOf(a, b, c).forEach { assertTrue(it, !it.contains('/') && !it.contains(':')) }
    }

    @Test
    fun asciiNamesKeepLegacyResult() {
        // 老用户的可信副本/缓存文件继续命中
        assertEquals("py_xl01.py", ExecutableTrust.trustedName("py:xl01.py"))
        assertEquals("drpy_a-b.js", ExecutableTrust.trustedName("drpy:a-b.js"))
        assertEquals(
            File(File("/f", "exec_trusted"), "py_xl01.py"),
            ExecutableTrust.trustedPath(File("/f"), "py:xl01.py"),
        )
        assertEquals(
            "py_xl01_py",
            SafeFileName.encode("py_xl01.py", Regex("[^a-zA-Z0-9_-]"), 80, "default"),
        )
    }

    @Test
    fun homeCacheStyleKeysDoNotCollide() {
        val unsafe = Regex("[^a-zA-Z0-9_-]")
        val a = SafeFileName.encode("py_红果短剧.py", unsafe, 80, "default")
        val b = SafeFileName.encode("py_西瓜影视.py", unsafe, 80, "default")
        assertNotEquals(a, b)
        assertTrue(a.length <= 80)
        // 中文分类 id
        assertNotEquals(
            SafeFileName.encode("短剧", unsafe, 40, "0"),
            SafeFileName.encode("电影", unsafe, 40, "0"),
        )
        assertEquals("0", SafeFileName.encode("", unsafe, 40, "0"))
    }

    @Test
    fun longKeysTruncatedButUnique() {
        val unsafe = Regex("[^a-zA-Z0-9_-]")
        val base = "x".repeat(100)
        val a = SafeFileName.encode(base + "a", unsafe, 60, "d")
        val b = SafeFileName.encode(base + "b", unsafe, 60, "d")
        assertNotEquals(a, b)
        assertTrue(a.length <= 60)
    }
}
