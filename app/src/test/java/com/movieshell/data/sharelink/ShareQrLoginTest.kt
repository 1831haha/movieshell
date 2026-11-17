package com.movieshell.data.sharelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.5：扫码登录纯函数（二维码内容拼装、百度 unicast 解析）。 */
class ShareQrLoginTest {

    @Test
    fun buildQuarkQrContent_containsTokenAndClientId() {
        val content = ShareQrLogin.buildQuarkQrContent("tok123")

        assertTrue(content.startsWith("https://su.quark.cn/4_eMHBJ"))
        assertTrue(content.contains("token=tok123"))
        assertTrue(content.contains("client_id=532"))
    }

    @Test
    fun buildUcQrContent_containsTokenAndClientId() {
        val content = ShareQrLogin.buildUcQrContent("tok456")

        assertTrue(content.startsWith("https://broccoli.uc.cn/"))
        assertTrue(content.contains("token=tok456"))
        assertTrue(content.contains("client_id=381"))
    }

    @Test
    fun buildUopQrContent_dispatchesByPlatform() {
        assertTrue(
            ShareQrLogin.buildUopQrContent(SharePlatform.QUARK, "t1").contains("su.quark.cn"),
        )
        assertTrue(
            ShareQrLogin.buildUopQrContent(SharePlatform.UC, "t1").contains("broccoli.uc.cn"),
        )
    }

    @Test
    fun parseBaiduV_parsesBdussAndStoken() {
        val login = ShareQrLogin.parseBaiduV("bduss=BD123&stoken=ST456&extra=x")!!

        assertEquals("BD123", login.bduss)
        assertEquals("ST456", login.stoken)
    }

    @Test
    fun parseBaiduV_handlesUrlEncodedValue() {
        val login = ShareQrLogin.parseBaiduV("bduss=BD%3D123&stoken=ST")!!

        assertEquals("BD=123", login.bduss)
        assertEquals("ST", login.stoken)
    }

    @Test
    fun parseBaiduV_missingFieldReturnsNull() {
        assertNull(ShareQrLogin.parseBaiduV("bduss=BD123"))
        assertNull(ShareQrLogin.parseBaiduV("x=1&y=2"))
        assertNull(ShareQrLogin.parseBaiduV(""))
    }
}
