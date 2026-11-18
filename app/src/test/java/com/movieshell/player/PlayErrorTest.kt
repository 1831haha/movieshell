package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayErrorTest {

    @Test fun `classify fallback never echoes the raw error string`() {
        val raw = "boom https://cdn.example.com/v.mp4?auth_key=secret123&e=999"
        val err = PlayError.classify(raw)
        assertEquals("播放失败", err.title)
        assertFalse(err.reason.contains("https"))
        assertFalse(err.reason.contains("auth_key"))
        assertFalse(err.reason.contains("secret123"))
        assertFalse(err.reason.contains(raw))
    }

    @Test fun `classify keeps known categories`() {
        assertEquals("网络连接超时", PlayError.classify("read timeout").title)
        assertEquals("视频资源不存在", PlayError.classify("HTTP 404").title)
        assertEquals("播放失败", PlayError.classify("").title)
    }
}
