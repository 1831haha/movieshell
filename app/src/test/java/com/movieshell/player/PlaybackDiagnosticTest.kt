package com.movieshell.player

import com.movieshell.core.log.LogBus
import com.movieshell.domain.model.PlayerEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * v1.4.7：首屏分段诊断胶水层单测（假时钟＋开关覆盖，不碰 Android）。
 * 覆盖：同一地址的 effect 重入（全屏跳页/返回小窗）不断 Attempt、打点继续落；
 * 换地址建新会话、旧 Attempt 收尾；退出清快照；开关关闭时全丢、不崩。
 * 会话身份凭 id 认领：beginSession 返回会话 id，旧 effect 凭旧 id 收尾
 * 误杀不了新会话；重入与重播凭 isContinuation 区分（播放器是否正在
 * 服务该地址），不凭 Attempt 是否已终结。
 * 注意：PlaybackDiagnostic 单例跨测试方法共享，setUp 里先清掉上一个测试的残留。
 */
class PlaybackDiagnosticTest {

    /** 假时钟：now 可拨，clock 读它。 */
    private class FakeClock(var now: Long) {
        val fn: () -> Long = { now }
    }

    private val clock = FakeClock(1000L)
    private val urlA = "https://a.example.com/one.m3u8"
    private val urlB = "https://b.example.com/two.m3u8"

    @Before
    fun setUp() {
        PlaybackDiagnostic.enabledOverride = true
        PlaybackDiagnostic.testClock = clock.fn
        PlaybackDiagnostic.endSession()
        // 丢点行走 LogBus：先清掉上一个测试的残留，计数才准。
        LogBus.clear()
    }

    @After
    fun tearDown() {
        PlaybackDiagnostic.endSession()
        PlaybackDiagnostic.enabledOverride = null
        PlaybackDiagnostic.testClock = null
    }

    @Test
    fun resumeSameUrlKeepsAttemptAndMarks() {
        val idA = PlaybackDiagnostic.beginSession(urlA, false, false)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 1000L, false, urlA)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 210L)
        val before = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(before)
        assertEquals(210L, before?.durations?.get(TraceStage.DECODE))

        // 模拟 effect 重入：全屏跳页/返回小窗，同一地址再次 beginSession，
        // 播放器当时正在服务该地址（isContinuation=true）。
        assertEquals(idA, PlaybackDiagnostic.beginSession(urlA, false, true))
        // 接续播放不调 play()，打点必须继续落进同一个 Attempt。
        clock.now = 2000L
        PlaybackDiagnostic.markPoint(TraceStage.FIRST_FRAME)
        val after = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(after)
        assertEquals(before?.sessionId, after?.sessionId)
        assertEquals(before?.attemptIndex, after?.attemptIndex)
        assertEquals(210L, after?.durations?.get(TraceStage.DECODE))
        // 首帧渲染段：起点取 T0（此前各段无终点），2000 - 1000 = 1000。
        assertEquals(1000L, after?.durations?.get(TraceStage.FIRST_FRAME))
    }

    @Test
    fun addressChangeEndsOldAttempt() {
        PlaybackDiagnostic.beginSession(urlA, false, false)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 1000L, false, urlA)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 210L)
        val before = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(before)

        // 换地址：新会话，旧 Attempt 按取消收尾，快照先空（新会话还没 Attempt）。
        PlaybackDiagnostic.beginSession(urlB, false, false)
        assertNull(PlaybackDiagnostic.snapshotFlow.value)
        // 旧 Attempt 的汇总行已打出，结局确实是取消（不是成功/超时）。
        assertEquals(
            1,
            LogBus.snapshot().count {
                it.tag == "PlaybackDiagnostic" && it.msg.contains("取消")
            },
        )
        // 新会话没建 Attempt 前打点被丢，不崩、快照仍空。
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 99L)
        assertNull(PlaybackDiagnostic.snapshotFlow.value)

        // 新 Attempt 建好后打点正常落，会话号与之前不同。
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 3000L, false, urlB)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 99L)
        val after = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(after)
        assertEquals(99L, after?.durations?.get(TraceStage.DECODE))
        assertNotEquals(before?.sessionId, after?.sessionId)
    }

    @Test
    fun endSessionClearsSnapshot() {
        PlaybackDiagnostic.beginSession(urlA, false, false)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 1000L, false, urlA)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 210L)
        assertNotNull(PlaybackDiagnostic.snapshotFlow.value)

        PlaybackDiagnostic.endSession()
        assertNull(PlaybackDiagnostic.snapshotFlow.value)
        // 收尾后打点被丢，不崩、快照仍空。
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 55L)
        assertNull(PlaybackDiagnostic.snapshotFlow.value)
    }

    @Test
    fun switchOffDropsEverything() {
        PlaybackDiagnostic.enabledOverride = false
        PlaybackDiagnostic.beginSession(urlA, false, false)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 1000L, false, urlA)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 210L)
        assertNull(PlaybackDiagnostic.snapshotFlow.value)
    }

    /** 丢点日志行：tag 为 PlaybackDiagnostic 且内容含“打点被丢”。 */
    private fun dropLines() = LogBus.snapshot()
        .filter { it.tag == "PlaybackDiagnostic" && it.msg.contains("打点被丢") }

    @Test
    fun dropReasonLoggedOncePerSessionAlternating() {
        PlaybackDiagnostic.beginSession(urlA, false, false)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 1000L, false, urlA)
        // 原因 A：开关已关——连打两次只记一行。
        PlaybackDiagnostic.enabledOverride = false
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 1L)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 2L)
        assertEquals(1, dropLines().size)
        // 原因 B：尝试已终结——各记一次。
        PlaybackDiagnostic.enabledOverride = true
        PlaybackDiagnostic.finishAttempt(TraceOutcome.SUCCESS)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 3L)
        assertEquals(2, dropLines().size)
        // A 隔一个再出现：仍不重复记（单 lastDropKey 只压连续重复，这里会漏）。
        PlaybackDiagnostic.enabledOverride = false
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 4L)
        assertEquals(2, dropLines().size)
        // B 再出现：同样不重复记。
        PlaybackDiagnostic.enabledOverride = true
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 5L)
        assertEquals(2, dropLines().size)
        // 新会话：同一原因重新记——开关已关在新会话里再记一行。
        PlaybackDiagnostic.beginSession(urlB, false, false)
        PlaybackDiagnostic.enabledOverride = false
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 6L)
        assertEquals(3, dropLines().size)
    }

    @Test
    fun implicitSessionSurvivesSameUrlBeginSession() {
        // 没走 effect：先 beginAttempt 建隐式会话。
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 1000L, false, urlA)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 210L)
        val before = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(before)
        assertEquals(210L, before?.durations?.get(TraceStage.DECODE))

        // effect 随后以同一地址 beginSession：应认出同一播放，不重建会话。
        // 播放器当时正在服务该地址（play() 已调过，隐式会话在世）。
        PlaybackDiagnostic.beginSession(urlA, false, true)
        clock.now = 2000L
        PlaybackDiagnostic.markPoint(TraceStage.FIRST_FRAME)
        val after = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(after)
        assertEquals(before?.sessionId, after?.sessionId)
        assertEquals(before?.attemptIndex, after?.attemptIndex)
        assertEquals(210L, after?.durations?.get(TraceStage.DECODE))
        // 首帧渲染段：起点取 T0（此前各段无终点），2000 - 1000 = 1000。
        assertEquals(1000L, after?.durations?.get(TraceStage.FIRST_FRAME))
    }

    /**
     * 已终结 Attempt 的同地址重入（首帧后切全屏是常态：首帧一到 Attempt 即
     * 终结，终结不代表播放结束）：播放器当时正在服务该地址时保留会话，
     * 快照保留（浮层不丢）。
     */
    @Test
    fun finishedAttemptSameUrlContinuationKeepsSession() {
        PlaybackDiagnostic.beginSession(urlA, false, false)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 1000L, false, urlA)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 210L)
        PlaybackDiagnostic.finishAttempt(TraceOutcome.SUCCESS)
        val before = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(before)
        assertEquals(210L, before?.durations?.get(TraceStage.DECODE))

        // 同地址重入：播放器在播，已终结的 Attempt 不重建会话，快照保留。
        PlaybackDiagnostic.beginSession(urlA, false, true)
        val after = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(after)
        assertEquals(before?.sessionId, after?.sessionId)
        assertEquals(before?.attemptIndex, after?.attemptIndex)
        assertEquals(210L, after?.durations?.get(TraceStage.DECODE))

        // 换地址仍建新会话：旧 Attempt 已终结，不重复收尾；新会话快照先空。
        PlaybackDiagnostic.beginSession(urlB, false, false)
        assertNull(PlaybackDiagnostic.snapshotFlow.value)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 3000L, false, urlB)
        val third = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(third)
        assertNotEquals(before?.sessionId, third?.sessionId)
    }

    /**
     * 同地址真正重播（播完再点同一集）：播放器当时不在服务该地址时建新
     * 会话，不复用已终结 Attempt 的旧会话——与上一个用例成对，区分
     * “重入”与“重播”。
     */
    @Test
    fun sameUrlFreshPlayCreatesNewSession() {
        val idA = PlaybackDiagnostic.beginSession(urlA, false, false)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 1000L, false, urlA)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 210L)
        PlaybackDiagnostic.finishAttempt(TraceOutcome.SUCCESS)
        val before = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(before)

        // 播放器已不在服务该地址：同地址重播建新会话，快照先空。
        val idB = PlaybackDiagnostic.beginSession(urlA, false, false)
        assertNotEquals(idA, idB)
        assertNull(PlaybackDiagnostic.snapshotFlow.value)

        // 新 Attempt 建好后打点正常落，会话号与之前不同。
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 3000L, false, urlA)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 99L)
        val after = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(after)
        assertEquals(idB, after?.sessionId)
        assertEquals(99L, after?.durations?.get(TraceStage.DECODE))
    }

    /**
     * P0：旧 effect 的 finally 晚于新 effect 的 beginSession 跑时，凭旧 id
     * 收尾必须是 no-op，不能误杀新会话；新 effect 自己的 id 收尾才生效。
     */
    @Test
    fun staleEffectEndSessionCannotKillNewSession() {
        val idA = PlaybackDiagnostic.beginSession(urlA, false, false)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 1000L, false, urlA)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 210L)
        val before = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(before)
        assertEquals(idA, before?.sessionId)

        // 切集：新 effect 建会话 B、起新 Attempt。
        val idB = PlaybackDiagnostic.beginSession(urlB, false, false)
        assertNotEquals(idA, idB)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 3000L, false, urlB)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 99L)
        val bSnap = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(bSnap)
        assertEquals(idB, bSnap?.sessionId)
        assertEquals(99L, bSnap?.durations?.get(TraceStage.DECODE))

        // 旧 effect 的 finally 姗姗来迟：凭旧 id 收尾是 no-op，新会话安然无恙。
        PlaybackDiagnostic.endSession(idA)
        val stillB = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(stillB)
        assertEquals(idB, stillB?.sessionId)
        assertEquals(99L, stillB?.durations?.get(TraceStage.DECODE))

        // 新 effect 自己的 id 收尾才生效。
        PlaybackDiagnostic.endSession(idB)
        assertNull(PlaybackDiagnostic.snapshotFlow.value)
    }

    /**
     * GPT 第二轮：凭 NO_SESSION_ID 收尾必须是 no-op——进页时开关关着、
     * 后来开关打开、别的入口建了新会话，本页离开时不能凭哨兵 id
     * 误杀新会话；finally 里传 NO_SESSION_ID 同样走这条路，也安全。
     */
    @Test
    fun noSessionIdEndSessionIsNoop() {
        // 进页时开关关着：beginSession 返回哨兵，不建会话。
        PlaybackDiagnostic.enabledOverride = false
        assertEquals(
            PlaybackDiagnostic.NO_SESSION_ID,
            PlaybackDiagnostic.beginSession(urlA, false, false),
        )
        assertNull(PlaybackDiagnostic.snapshotFlow.value)

        // 开关后来打开，别的入口建了会话 B 并打点。
        PlaybackDiagnostic.enabledOverride = true
        val idB = PlaybackDiagnostic.beginSession(urlB, false, false)
        assertNotEquals(PlaybackDiagnostic.NO_SESSION_ID, idB)
        PlaybackDiagnostic.beginAttempt(PlayerEngine.MEDIA3, 3000L, false, urlB)
        PlaybackDiagnostic.markMeasured(TraceStage.DECODE, 99L)
        val bSnap = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(bSnap)
        assertEquals(idB, bSnap?.sessionId)
        assertEquals(99L, bSnap?.durations?.get(TraceStage.DECODE))

        // 凭哨兵 id 收尾是 no-op：B 的会话与快照安然无恙。
        PlaybackDiagnostic.endSession(PlaybackDiagnostic.NO_SESSION_ID)
        val stillB = PlaybackDiagnostic.snapshotFlow.value
        assertNotNull(stillB)
        assertEquals(idB, stillB?.sessionId)
        assertEquals(99L, stillB?.durations?.get(TraceStage.DECODE))

        // B 自己的 id 收尾才生效。
        PlaybackDiagnostic.endSession(idB)
        assertNull(PlaybackDiagnostic.snapshotFlow.value)
    }

    @Test
    fun sessionKeepsUrl() {
        val session = FirstScreenSession(9L, false, url = urlA, clock = clock.fn)
        assertEquals(urlA, session.url)
    }
}
