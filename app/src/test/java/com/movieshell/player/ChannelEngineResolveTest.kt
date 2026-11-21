package com.movieshell.player

import com.movieshell.domain.model.PlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.8 A2：引擎决策与三条覆盖行为（纯函数单测）。
 *
 * 优先级铁律（不重排）：临时覆盖 > 协议强制（含自签 Emby 证书分支）>
 * 初始化失败回退 > 频道偏好 > 全局偏好。其中协议强制/自签/初始化失败
 * 回退在 play() 里是“读完偏好后、按条件改写 eng 并写覆盖”的两步，
 * 不是 resolveEngine 内的排序——本文件锁定每一步的判定条件。
 *
 * 6 处透传决策表：PlayerScreen 内 6 处 EngineHost.play 调用（旋转重播/
 * 重试入口/主起播/挂字幕重播/手动切核/清 Referer 重试）全部透传本页
 * channelId；组合页面不可在纯 JVM 单测里跑，决策本身（有 id 走本台偏好、
 * 无 id 走全局）用 resolveEngine 按调用点逐条锁定，调用点本身另由
 * grep 核对（6 处均带 `channelId = channelId`）。
 */
class ChannelEngineResolveTest {

    private val global = PlayerEngine.MEDIA3
    private val channelPref = PlayerEngine.LIBMPV

    // ---- resolveEngine：覆盖 > 频道 > 全局 ----

    @Test
    fun `override beats channel pref`() {
        assertEquals(
            PlayerEngine.MEDIA3,
            EngineHost.resolveEngine(channelPref, global, PlayerEngine.MEDIA3),
        )
    }

    @Test
    fun `channel pref beats global`() {
        assertEquals(channelPref, EngineHost.resolveEngine(channelPref, global, null))
    }

    @Test
    fun `no channel pref follows global`() {
        assertEquals(global, EngineHost.resolveEngine(null, global, null))
        assertEquals(PlayerEngine.LIBMPV, EngineHost.resolveEngine(null, PlayerEngine.LIBMPV, null))
    }

    // ---- 6 处透传决策表（每处：有 id 走本台，无 id 走全局） ----

    @Test
    fun `resolveEngine rotation replay uses channel pref when id present else global`() {
        assertEquals(channelPref, EngineHost.resolveEngine(channelPref, global, null))
        assertEquals(global, EngineHost.resolveEngine(null, global, null))
    }

    @Test
    fun `resolveEngine retry entry uses channel pref when id present else global`() {
        assertEquals(channelPref, EngineHost.resolveEngine(channelPref, global, null))
        assertEquals(global, EngineHost.resolveEngine(null, global, null))
    }

    @Test
    fun `resolveEngine main start uses channel pref when id present else global`() {
        assertEquals(channelPref, EngineHost.resolveEngine(channelPref, global, null))
        assertEquals(global, EngineHost.resolveEngine(null, global, null))
    }

    @Test
    fun `resolveEngine replay with subtitle uses channel pref when id present else global`() {
        assertEquals(channelPref, EngineHost.resolveEngine(channelPref, global, null))
        assertEquals(global, EngineHost.resolveEngine(null, global, null))
    }

    @Test
    fun `resolveEngine manual engine switch replay uses channel pref when id present else global`() {
        // 关键一处：切核写入的是本台偏好，此次 play 必须带 channelId，
        // 否则读全局，切换看起来“没切上”。
        assertEquals(channelPref, EngineHost.resolveEngine(channelPref, global, null))
        assertEquals(global, EngineHost.resolveEngine(null, global, null))
    }

    @Test
    fun `resolveEngine clear referer retry uses channel pref when id present else global`() {
        assertEquals(channelPref, EngineHost.resolveEngine(channelPref, global, null))
        assertEquals(global, EngineHost.resolveEngine(null, global, null))
    }

    // ---- 协议强制：仅在决策引擎是 MEDIA3 时写 LIBMPV 覆盖 ----

    @Test
    fun `protocol forces libmpv only when engine is media3`() {
        assertEquals(
            PlayerEngine.LIBMPV,
            EngineHost.protocolForcedEngine(true, PlayerEngine.MEDIA3),
        )
        // 已是 LIBMPV 时不写覆盖。
        assertNull(EngineHost.protocolForcedEngine(true, PlayerEngine.LIBMPV))
        // 非协议地址不强制。
        assertNull(EngineHost.protocolForcedEngine(false, PlayerEngine.MEDIA3))
        assertNull(EngineHost.protocolForcedEngine(false, PlayerEngine.LIBMPV))
    }

    // ---- 协议分支写覆盖后：分发引擎必须跟上覆盖读 ----
    // v1.4.8 总协调复核发现的回归：A2 把 play() 里的 var eng = engine()
    // （分支之后读，含刚写的协议覆盖）改成 var eng = baseEngine（分支
    // 之前算的），RTMP/RTP/RTSP 会按 baseEngine 走 Media3——Media3
    // 不吃这三种协议。锁死：覆盖非空时分发引擎 = 覆盖。

    @Test
    fun `protocol override wins over base engine for dispatch`() {
        assertEquals(
            PlayerEngine.LIBMPV,
            EngineHost.engineAfterProtocolOverride(PlayerEngine.LIBMPV, PlayerEngine.MEDIA3),
        )
    }

    @Test
    fun `no override keeps base engine for dispatch`() {
        assertEquals(
            PlayerEngine.MEDIA3,
            EngineHost.engineAfterProtocolOverride(null, PlayerEngine.MEDIA3),
        )
        assertEquals(
            PlayerEngine.LIBMPV,
            EngineHost.engineAfterProtocolOverride(null, PlayerEngine.LIBMPV),
        )
    }

    @Test
    fun `existing override still wins over base engine for dispatch`() {
        assertEquals(
            PlayerEngine.MEDIA3,
            EngineHost.engineAfterProtocolOverride(PlayerEngine.MEDIA3, PlayerEngine.LIBMPV),
        )
    }

    // ---- 自签分支：已有覆盖时不插手 ----

    @Test
    fun `cert bypass only without override and on libmpv`() {
        assertTrue(
            EngineHost.shouldBypassCertToMedia3(
                true, "https://emby.example.com/x", PlayerEngine.LIBMPV, null,
            ),
        )
        // 已有临时覆盖时不插手。
        assertFalse(
            EngineHost.shouldBypassCertToMedia3(
                true, "https://emby.example.com/x", PlayerEngine.LIBMPV, PlayerEngine.LIBMPV,
            ),
        )
        // 决策引擎已是 Media3 时不用切。
        assertFalse(
            EngineHost.shouldBypassCertToMedia3(
                true, "https://emby.example.com/x", PlayerEngine.MEDIA3, null,
            ),
        )
        // 不放行证书 / 非 https 时不切。
        assertFalse(
            EngineHost.shouldBypassCertToMedia3(
                false, "https://emby.example.com/x", PlayerEngine.LIBMPV, null,
            ),
        )
        assertFalse(
            EngineHost.shouldBypassCertToMedia3(
                true, "http://192.168.1.10:8096/x", PlayerEngine.LIBMPV, null,
            ),
        )
    }

    // ---- 初始化失败回退：无条件写 MEDIA3 覆盖（会盖掉已有临时覆盖） ----

    @Test
    fun `init failed fallback returns media3 regardless of override`() {
        assertEquals(
            PlayerEngine.MEDIA3,
            EngineHost.resolveInitFailedFallback(PlayerEngine.LIBMPV, true),
        )
        // 未失败过不回退；决策引擎已是 Media3 也不动。
        assertEquals(
            PlayerEngine.LIBMPV,
            EngineHost.resolveInitFailedFallback(PlayerEngine.LIBMPV, false),
        )
        assertEquals(
            PlayerEngine.MEDIA3,
            EngineHost.resolveInitFailedFallback(PlayerEngine.MEDIA3, true),
        )
        // 注意：本判定不看临时覆盖——play() 里该分支没有 _override 守卫，
        // 与自签分支“已有覆盖不插手”不同，现状锁定，不“顺手统一”。
    }

    // ---- 覆盖不清跨台：切台换地址后覆盖失效 ----

    @Test
    fun `override is cleared on address change but kept when resuming same player`() {
        // 真正换地址（切台/切集/换线路）：清覆盖，下一台回到偏好决策。
        assertTrue(EngineHost.shouldClearOverrideOnAddressChange(resuming = false))
        // 接续同一播放器（全屏跳页/返回小窗）：不清，自动换内核的成果保留。
        assertFalse(EngineHost.shouldClearOverrideOnAddressChange(resuming = true))
    }
}
