package com.movieshell.data.sharelink

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.3.10：历史记录的纯拼装逻辑（load/add/clear 要走真 SharedPreferences，纯 JUnit 测不了）。 */
class ShareLinkPrefsTest {

    @Test
    fun key_history_isStable() {
        // 键名改了会丢掉用户已存的历史记录，改键必须同步做迁移。
        assertEquals("sharelink_history", ShareLinkPrefs.KEY_HISTORY)
    }

    @Test
    fun mergeHistory_newestFirstAndDedups() {
        val existing = listOf("https://pan.quark.cn/s/aaa", "https://pan.quark.cn/s/bbb")
        // 已有的链接再解析一次，提到最前面，不重复。
        val merged = ShareLinkPrefs.mergeHistory(existing, "https://pan.quark.cn/s/bbb")
        assertEquals(
            listOf("https://pan.quark.cn/s/bbb", "https://pan.quark.cn/s/aaa"),
            merged,
        )
    }

    @Test
    fun mergeHistory_capsAtMax() {
        val existing = (1..10).map { "https://pan.quark.cn/s/$it" }
        val merged = ShareLinkPrefs.mergeHistory(existing, "https://pan.quark.cn/s/new")
        assertEquals(ShareLinkPrefs.MAX_HISTORY, merged.size)
        assertEquals("https://pan.quark.cn/s/new", merged.first())
        // 最老的一条被挤掉。
        assertEquals("https://pan.quark.cn/s/9", merged.last())
    }

    @Test
    fun mergeHistory_blankUrlKeepsExisting() {
        val existing = listOf("https://pan.quark.cn/s/aaa")
        assertEquals(existing, ShareLinkPrefs.mergeHistory(existing, "   "))
    }
}
