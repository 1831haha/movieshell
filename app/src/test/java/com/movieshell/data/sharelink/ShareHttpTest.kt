package com.movieshell.data.sharelink

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.3.22：Set-Cookie 解析只测键与值形态，不涉及真实账号或外网。 */
class ShareHttpTest {

    private val url = "https://drive-pc.quark.cn/1/clouddrive/file/download".toHttpUrl()

    private fun pugs(vararg setCookies: String): String =
        ShareHttp.pugsFromSetCookie(url, Headers.Builder().also { b ->
            setCookies.forEach { b.add("Set-Cookie", it) }
        }.build())

    @Test
    fun pugsFromSetCookie_standardAttributes() {
        assertEquals(
            "abc123",
            pugs("__pugs=abc123; Path=/; Domain=.drive.quark.cn; HttpOnly; Secure"),
        )
    }

    @Test
    fun pugsFromSetCookie_ignoresOtherCookies() {
        assertEquals(
            "only-me",
            pugs(
                "__puus=aaa; Path=/",
                "__pugs=only-me; Path=/",
                "_UP_A=bbb; Path=/",
            ),
        )
    }

    @Test
    fun pugsFromSetCookie_caseInsensitiveName() {
        assertEquals("AbC", pugs("__PUGS=AbC; Path=/"))
    }

    @Test
    fun pugsFromSetCookie_leadingSpaceAndQuotedValue() {
        assertEquals("xyz", pugs(" __pugs=\"xyz\"; Path=/"))
    }

    @Test
    fun pugsFromSetCookie_emptyWhenMissing() {
        assertEquals("", pugs("__puus=aaa; Path=/", "other=1; Path=/"))
        assertEquals("", ShareHttp.pugsFromSetCookie(url, Headers.Builder().build()))
    }
}
