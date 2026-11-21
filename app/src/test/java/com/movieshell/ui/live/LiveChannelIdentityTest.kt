package com.movieshell.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.8 A3(f)：展示层按频道 id 比较，不按 name。 */
class LiveChannelIdentityTest {

    @Test
    fun sameIdShowsNowTitle() {
        assertTrue(isSameChannel("m3u:央视|http://a/cctv1.m3u8", "m3u:央视|http://a/cctv1.m3u8"))
        assertEquals(
            "新闻联播",
            nowTitleForChannel("m3u:央视|http://a/cctv1.m3u8", "m3u:央视|http://a/cctv1.m3u8", "新闻联播"),
        )
    }

    @Test
    fun sameNameDifferentIdShowsNothing() {
        // 同名多源：选中台的 id 与另一源同名台的 id 不同，只给选中台显示节目名。
        val selectedId = "m3u:央视|http://a/cctv1.m3u8"
        val otherSourceId = "m3u:备用|http://b/cctv1.m3u8"
        assertFalse(isSameChannel(selectedId, otherSourceId))
        assertNull(nowTitleForChannel(selectedId, otherSourceId, "新闻联播"))
    }

    @Test
    fun noSelectionShowsNothing() {
        assertFalse(isSameChannel(null, "m3u:央视|http://a/cctv1.m3u8"))
        assertNull(nowTitleForChannel(null, "m3u:央视|http://a/cctv1.m3u8", "新闻联播"))
    }

    @Test
    fun sameIdWithoutEpgTitleIsNull() {
        assertNull(
            nowTitleForChannel("id-1", "id-1", null),
        )
    }
}
