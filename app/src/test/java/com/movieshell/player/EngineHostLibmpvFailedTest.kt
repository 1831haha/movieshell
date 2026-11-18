package com.movieshell.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v1.3.21 M15：libmpv 失败 path 满 50 只淘汰最旧一条，不再全清（纯函数逻辑）。 */
class EngineHostLibmpvFailedTest {

    @Before fun clear() {
        EngineHost.clearLibmpvFailed()
    }

    @Test fun `full set evicts only the oldest entry`() {
        for (i in 0 until 51) {
            EngineHost.markLibmpvFailed("https://cdn.example.com/v$i/seg.m3u8?auth_key=abc")
        }
        assertFalse(EngineHost.libmpvFailed("https://cdn.example.com/v0/seg.m3u8"))
        assertTrue(EngineHost.libmpvFailed("https://cdn.example.com/v1/seg.m3u8"))
        assertTrue(EngineHost.libmpvFailed("https://cdn.example.com/v50/seg.m3u8?auth_key=xyz"))
    }

    @Test fun `same path with different query is one entry`() {
        EngineHost.markLibmpvFailed("https://cdn.example.com/a.m3u8?auth_key=1")
        assertTrue(EngineHost.libmpvFailed("https://cdn.example.com/a.m3u8?auth_key=2"))
        EngineHost.markLibmpvFailed("https://cdn.example.com/a.m3u8?auth_key=2")
        assertTrue(EngineHost.libmpvFailed("https://cdn.example.com/a.m3u8"))
    }

    @Test fun `blank url is ignored`() {
        EngineHost.markLibmpvFailed("")
        EngineHost.markLibmpvFailed("   ")
        assertFalse(EngineHost.libmpvFailed(""))
        assertFalse(EngineHost.libmpvFailed("https://cdn.example.com/a.m3u8"))
    }
}
