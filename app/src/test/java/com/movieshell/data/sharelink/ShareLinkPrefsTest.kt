package com.movieshell.data.sharelink

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.3.7：上次链接的键名（纯常量；load/save/clear 要走真 SharedPreferences，纯 JUnit 测不了）。 */
class ShareLinkPrefsTest {

    @Test
    fun key_lastUrl_isStable() {
        // 键名改了会丢掉用户已存的上次链接，改键必须同步做迁移。
        assertEquals("sharelink_last_url", ShareLinkPrefs.KEY_LAST_URL)
    }
}
