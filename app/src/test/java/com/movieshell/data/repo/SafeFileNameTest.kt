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
    fun safeNamesKeepIdentity() {
        // 不需要替换任何字符的名字原样保留（老文件继续命中）
        assertEquals("xl01.py", ExecutableTrust.trustedName("xl01.py"))
        assertEquals("a-b_c", SafeFileName.encode("a-b_c", Regex("[^a-zA-Z0-9_-]"), 80, "default"))
        assertEquals(
            File(File("/f", "exec_trusted"), "xl01.py"),
            ExecutableTrust.trustedPath(File("/f"), "xl01.py"),
        )
        // 有替换时保留扩展名、无路径逃逸
        val t = ExecutableTrust.trustedName("py:xl01.py")
        assertTrue(t, t.startsWith("py_xl01_") && t.endsWith(".py") && !t.contains(':'))
    }

    @Test
    fun replacedCharsNeverCollide() {
        // v0.294.5：. : / 被替换成 _ 后不能与原本就是 _ 的名字撞车
        assertNotEquals(ExecutableTrust.trustedName("py:xl01.py"), ExecutableTrust.trustedName("py_xl01.py"))
        val unsafe = Regex("[^a-zA-Z0-9_-]")
        assertNotEquals(
            SafeFileName.encode("py_xl01.py", unsafe, 80, "default"),
            SafeFileName.encode("py_xl01_py", unsafe, 80, "default"),
        )
        assertNotEquals(
            SafeFileName.encode("a/b", unsafe, 80, "d"),
            SafeFileName.encode("a:b", unsafe, 80, "d"),
        )
        // 旧算法结果仍可算出（迁移查找用）
        assertEquals("py_xl01.py", SafeFileName.legacyEncode("py:xl01.py", Regex("[^A-Za-z0-9._-]+"), 200, "_", keepExt = true))
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

    @Test
    fun subscriptionPyKeyKeepsPyWhenSourceExtGiven() {
        // 订阅站信任 key 无后缀；公开文件是 .py → 副本必须带 .py，否则 Chaquopy 拒载
        val key = "site:sub_f3ep6w_xy_nasa"
        val without = ExecutableTrust.trustedName(key)
        val with = ExecutableTrust.trustedName(key, "py")
        assertTrue(without, !without.endsWith(".py"))
        assertTrue(with, with.endsWith(".py"))
        assertEquals(with, ExecutableTrust.forceScriptExt(without, "py"))
        assertEquals("a.py", ExecutableTrust.forceScriptExt("a.py", "py"))
        assertEquals("a", ExecutableTrust.forceScriptExt("a", "txt")) // 非脚本后缀不硬加
    }

}
