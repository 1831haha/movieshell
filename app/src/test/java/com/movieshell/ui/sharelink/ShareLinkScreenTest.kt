package com.movieshell.ui.sharelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.3.7：长按复制的纯函数与提示文案（Composable 内的状态流转纯 JUnit 测不了）。 */
class ShareLinkScreenTest {

    @Test
    fun copyTextForLink_blank_returnsNull() {
        assertNull(copyTextForLink(""))
        assertNull(copyTextForLink("   "))
    }

    @Test
    fun copyTextForLink_nonBlank_returnsWholeText() {
        val url = "https://pan.quark.cn/s/fake1234567890"
        assertEquals(url, copyTextForLink(url))
    }

    @Test
    fun copyToastTexts_areStable() {
        assertEquals("已复制", SHARE_LINK_COPY_DONE)
        assertEquals("没有可复制的内容", SHARE_LINK_COPY_EMPTY)
    }
}
