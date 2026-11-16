package com.movieshell.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.2.14：Emby 自签证书播放——放行判定与路由参数解析（纯函数）。 */
class EmbyPlaybackCertTest {

    @Test fun `bypass only when ignoreCert and https`() {
        assertTrue(
            EngineHost.shouldBypassCert(
                true, "https://emby.example.com/emby/Videos/1/stream?Static=true",
            ),
        )
        assertFalse(
            EngineHost.shouldBypassCert(
                true, "http://192.168.1.10:8096/emby/Videos/1/stream",
            ),
        )
        assertFalse(
            EngineHost.shouldBypassCert(
                false, "https://emby.example.com/emby/Videos/1/stream",
            ),
        )
        assertFalse(
            EngineHost.shouldBypassCert(
                false, "http://192.168.1.10:8096/emby/Videos/1/stream",
            ),
        )
    }

    @Test fun `bypass tolerates case and surrounding spaces`() {
        assertTrue(EngineHost.shouldBypassCert(true, "HTTPS://emby.example.com/x"))
        assertTrue(EngineHost.shouldBypassCert(true, "  https://emby.example.com/x  "))
        assertFalse(EngineHost.shouldBypassCert(true, ""))
        assertFalse(EngineHost.shouldBypassCert(true, "magnet:?xt=urn:btih:abc"))
    }

    @Test fun `parseIgnoreCertArg only 1 means bypass`() {
        assertTrue(parseIgnoreCertArg("1"))
        assertFalse(parseIgnoreCertArg("0"))
        assertFalse(parseIgnoreCertArg(null))
        assertFalse(parseIgnoreCertArg(""))
        assertFalse(parseIgnoreCertArg("true"))
    }
}
