package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacCmsPlayerTest {
    @Test fun `encrypt 0 plain url with escaped slashes`() {
        val html = """<script>var player_aaaa={"flag":"play","encrypt":0,"from":"ffm3u8","url":"https:\/\/a.com\/v\/index.m3u8"}</script>"""
        val r = MacCmsPlayer.extract(html)!!
        assertEquals("https://a.com/v/index.m3u8", r.url)
        assertEquals("ffm3u8", r.from)
    }

    @Test fun `encrypt 1 unescape`() {
        val html = """var player_data={"encrypt":1,"url":"https%3A%2F%2Fb.com%2F1.mp4"};"""
        assertEquals("https://b.com/1.mp4", MacCmsPlayer.extract(html)!!.url)
    }

    @Test fun `encrypt 2 base64 then unescape`() {
        val b64 = java.util.Base64.getEncoder().encodeToString("https%3A%2F%2Fc.com%2Fx.m3u8".toByteArray())
        val html = """var player_aaaa={"encrypt":2,"url":"$b64"}</script>"""
        assertEquals("https://c.com/x.m3u8", MacCmsPlayer.extract(html)!!.url)
    }

    @Test fun `unicode escapes and bad sequences`() {
        assertEquals("中a%zz", MacCmsPlayer.unescape("%u4E2Da%zz"))
        assertEquals("%", MacCmsPlayer.unescape("%"))
    }

    @Test fun `no player returns null`() {
        assertNull(MacCmsPlayer.extract("<html>nothing</html>"))
        assertNull(MacCmsPlayer.extract("""var player_aaaa={"url":""};"""))
    }

    @Test fun `absolutize`() {
        assertEquals("https://x.com/a.m3u8", MacCmsPlayer.absolutize("//x.com/a.m3u8", "https://p.com/play/1"))
        assertEquals("https://p.com/a.m3u8", MacCmsPlayer.absolutize("/a.m3u8", "https://p.com/play/1"))
        assertNull(MacCmsPlayer.absolutize("abc123", "https://p.com/play/1"))
    }
}
