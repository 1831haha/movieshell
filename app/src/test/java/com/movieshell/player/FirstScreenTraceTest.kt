package com.movieshell.player

import org.junit.Assert.*
import org.junit.Test

/**
 * v1.4.6 A1.1：首屏分段诊断纯模型单测（假时钟，不碰 Android）。
 * 覆盖：正常全段、某段缺失、内核切换不串线、30 秒超时、缓存命中/预解析命中、
 * 连接复用、DNS 失败记类名、分片失败上限、日志行格式、点事件段起点链。
 */
class FirstScreenTraceTest {

    /** 假时钟：now 可拨，clock 读它。 */
    private class FakeClock(var now: Long) {
        val fn: () -> Long = { now }
    }

    private fun fullAttempt(clock: FakeClock): FirstScreenAttempt {
        val attempt = FirstScreenAttempt(1L, "MEDIA3", 1000L, false, clock.fn)
        clock.now = 1000L
        attempt.markMeasured(TraceStage.RESOLVE, 412L)
        attempt.markStart(TraceStage.DNS, 1010L)
        attempt.markEnd(TraceStage.DNS, 1030L)
        attempt.markStart(TraceStage.CONNECT, 1030L)
        attempt.markEnd(TraceStage.CONNECT, 1060L)
        attempt.markStart(TraceStage.OPEN, 1060L)
        attempt.markEnd(TraceStage.OPEN, 1440L)
        attempt.noteResponseWait(200L)
        attempt.noteFirstByte(300L)
        attempt.markStart(TraceStage.PREPARE, 1440L)
        attempt.markEnd(TraceStage.PREPARE, 1536L)
        attempt.markMeasured(TraceStage.DECODE, 210L)
        attempt.markPoint(TraceStage.FIRST_DECODE, 1834L, approximate = true)
        attempt.markPoint(TraceStage.FIRST_FRAME, 1846L)
        return attempt
    }

    @Test fun fullStagesDurationsAndTotal() {
        val clock = FakeClock(1000L)
        val attempt = fullAttempt(clock)
        val snap = attempt.finish(TraceOutcome.SUCCESS, 1846L)
        assertEquals(412L, snap.durations[TraceStage.RESOLVE])
        assertEquals(20L, snap.durations[TraceStage.DNS])
        assertEquals(30L, snap.durations[TraceStage.CONNECT])
        assertEquals(380L, snap.durations[TraceStage.OPEN])
        assertEquals(96L, snap.durations[TraceStage.PREPARE])
        assertEquals(210L, snap.durations[TraceStage.DECODE])
        // 点事件段：起点取前序最近一段终点——解码 210ms 是 measured，无终点，
        // 往前找媒体准备终点 1536 → 首帧解码 = 1834 - 1536 = 298。
        assertEquals(298L, snap.durations[TraceStage.FIRST_DECODE])
        // 首帧渲染 = 1846 - 1834（首帧解码点）= 12；总计 = 1846 - 1000 = 846。
        assertEquals(12L, snap.durations[TraceStage.FIRST_FRAME])
        assertEquals(846L, snap.totalMs)
        assertTrue(snap.approximateStages.contains(TraceStage.FIRST_DECODE))
        assertFalse(snap.approximateStages.contains(TraceStage.FIRST_FRAME))
        assertEquals(200L, snap.responseWaitMs)
        assertEquals(300L, snap.firstByteMs)
        assertEquals(TraceOutcome.SUCCESS, snap.outcome)
    }

    @Test fun missingStageDurationIsNullAndTotalNullWithoutFirstFrame() {
        val clock = FakeClock(1000L)
        val attempt = FirstScreenAttempt(1L, "MEDIA3", 1000L, false, clock.fn)
        attempt.markStart(TraceStage.DNS, 1010L)
        attempt.markEnd(TraceStage.DNS, 1020L)
        // 建连/开流等都没走到，也不补 0。
        val snap = attempt.finish(TraceOutcome.FAILED, 2000L)
        assertEquals(10L, snap.durations[TraceStage.DNS])
        assertNull(snap.durations[TraceStage.CONNECT])
        assertNull(snap.durations[TraceStage.FIRST_FRAME])
        assertNull(snap.totalMs)
        assertEquals(TraceMissing.NOT_REACHED, snap.missing[TraceStage.CONNECT])
        assertEquals(TraceMissing.NOT_REACHED, snap.missing[TraceStage.FIRST_FRAME])
    }

    @Test fun kernelSwitchNewAttemptDoesNotInheritStages() {
        val clock = FakeClock(1000L)
        val session = FirstScreenSession(7L, false, clock = clock.fn)
        val first = session.newAttempt("MEDIA3", 1000L, 1L)
        first.markStart(TraceStage.DNS, 1010L)
        first.markEnd(TraceStage.DNS, 1030L)
        first.markFailed(TraceStage.CONNECT, "ConnectException")
        first.finish(TraceOutcome.FAILED, 1200L)
        val second = session.newAttempt("LIBMPV", 1200L, 2L)
        val snap = second.snapshot(1300L)
        assertNull(snap.durations[TraceStage.DNS])
        assertTrue(snap.errorClasses.isEmpty())
        assertEquals(2, session.attemptCount)
        val latest = session.snapshotLatest(1300L)
        assertNotNull(latest)
        assertEquals(7L, latest!!.sessionId)
        assertEquals(2, latest.attemptIndex)
        assertEquals("LIBMPV", latest.engine)
    }

    @Test fun thirtySecondTimeoutFillsUnfinishedStages() {
        val clock = FakeClock(1000L)
        val attempt = FirstScreenAttempt(1L, "MEDIA3", 1000L, false, clock.fn)
        attempt.markStart(TraceStage.DNS, 1010L)
        attempt.markEnd(TraceStage.DNS, 1020L)
        clock.now = 1000L + 30_001L
        assertTrue(attempt.isTimedOut())
        val snap = attempt.finish(TraceOutcome.TIMEOUT, clock.now)
        assertEquals(TraceMissing.TIMEOUT, snap.missing[TraceStage.FIRST_FRAME])
        assertEquals(TraceMissing.TIMEOUT, snap.missing[TraceStage.CONNECT])
        assertNull(snap.totalMs)
        assertEquals(TraceOutcome.TIMEOUT, snap.outcome)
    }

    @Test fun notTimedOutWithinThirtySeconds() {
        val clock = FakeClock(1000L)
        val attempt = FirstScreenAttempt(1L, "MEDIA3", 1000L, false, clock.fn)
        clock.now = 1000L + 30_000L
        assertFalse(attempt.isTimedOut())
    }

    @Test fun prefetchHitIsZeroMsWithNote() {
        val clock = FakeClock(1000L)
        val session = FirstScreenSession(3L, false, pendingPrefetchHit = true, clock = clock.fn)
        val attempt = session.newAttempt("MEDIA3", 1000L, 1L)
        val snap = attempt.snapshot(1100L)
        assertEquals(0L, snap.durations[TraceStage.RESOLVE])
        assertEquals(TraceMissing.PREFETCH_HIT, snap.missing[TraceStage.RESOLVE])
        assertEquals("0ms（预解析命中）", formatStageValue(snap, TraceStage.RESOLVE))
    }

    @Test fun pendingResolveMsAppliedOnlyToFirstAttempt() {
        val clock = FakeClock(1000L)
        val session = FirstScreenSession(3L, false, pendingResolveMs = 250L, clock = clock.fn)
        val first = session.newAttempt("MEDIA3", 1000L, 1L)
        assertEquals(250L, first.snapshot(1100L).durations[TraceStage.RESOLVE])
        val second = session.newAttempt("LIBMPV", 2000L, 2L)
        assertNull(second.snapshot(2100L).durations[TraceStage.RESOLVE])
    }

    @Test fun liveSessionResolveIsNotReached() {
        val clock = FakeClock(1000L)
        val session = FirstScreenSession(4L, true, clock = clock.fn)
        val attempt = session.newAttempt("MEDIA3", 1000L, 1L)
        val snap = attempt.snapshot(1100L)
        assertEquals(TraceMissing.NOT_REACHED, snap.missing[TraceStage.RESOLVE])
    }

    @Test fun connectionReuseMarkedNotOccurred() {
        val clock = FakeClock(1000L)
        val attempt = FirstScreenAttempt(1L, "MEDIA3", 1000L, false, clock.fn)
        attempt.markMissing(TraceStage.DNS, TraceMissing.NOT_OCCURRED)
        attempt.markMissing(TraceStage.CONNECT, TraceMissing.NOT_OCCURRED)
        val snap = attempt.snapshot(1100L)
        assertEquals("未发生（连接复用）", formatStageValue(snap, TraceStage.DNS))
        assertNull(snap.durations[TraceStage.DNS])
    }

    @Test fun dnsFailureKeepsOnlyClassName() {
        val clock = FakeClock(1000L)
        val attempt = FirstScreenAttempt(1L, "MEDIA3", 1000L, false, clock.fn)
        attempt.markFailed(TraceStage.DNS, "UnknownHostException")
        val snap = attempt.snapshot(1100L)
        assertEquals("失败（UnknownHostException）", formatStageValue(snap, TraceStage.DNS))
        assertEquals("UnknownHostException", snap.errorClasses[TraceStage.DNS])
    }

    @Test fun segmentFailuresCappedAtFiveWithOverflow() {
        val clock = FakeClock(1000L)
        val attempt = FirstScreenAttempt(1L, "MEDIA3", 1000L, false, clock.fn)
        repeat(7) { attempt.noteSegmentFailure("cdn.example.com", SegmentKind.SEGMENT, "IOException") }
        val snap = attempt.snapshot(1100L)
        assertEquals(5, snap.segmentFailures.size)
        assertEquals(2, snap.segmentFailureOverflow)
        assertEquals(1, snap.segmentFailures.first().requestIndex)
        assertEquals(5, snap.segmentFailures.last().requestIndex)
        val lines = formatSegmentFailureLines(snap.copy(sessionId = 7L, attemptIndex = 2))
        assertEquals(6, lines.size)
        assertTrue(lines[0].contains("分片失败#1 cdn.example.com 分片 IOException"))
        assertTrue(lines[5].contains("分片失败另有2条未列出"))
    }

    @Test fun classifySegmentLoadByDataTypeAndSuffix() {
        assertEquals(SegmentKind.MANIFEST, classifySegmentLoad("https://x/a.m3u8", 4))
        assertEquals(SegmentKind.KEY, classifySegmentLoad("https://x/k", 3))
        assertEquals(SegmentKind.MANIFEST, classifySegmentLoad("https://x/a.m3u8?token=1", null))
        assertEquals(SegmentKind.KEY, classifySegmentLoad("https://x/enc.key", null))
        assertEquals(SegmentKind.SEGMENT, classifySegmentLoad("https://x/seg123.ts", null))
        assertEquals(SegmentKind.SEGMENT, classifySegmentLoad("https://x/seg123.ts", 1))
    }

    @Test fun logLineMatchesDesignFormat() {
        val clock = FakeClock(1000L)
        val session = FirstScreenSession(7L, false, clock = clock.fn)
        val attempt = session.newAttempt("MEDIA3", 1000L, 1L)
        attempt.markMeasured(TraceStage.RESOLVE, 412L)
        attempt.markMissing(TraceStage.DNS, TraceMissing.UNAVAILABLE)
        attempt.markMissing(TraceStage.CONNECT, TraceMissing.UNAVAILABLE)
        attempt.markStart(TraceStage.OPEN, 1060L)
        attempt.markEnd(TraceStage.OPEN, 1440L)
        attempt.markStart(TraceStage.PREPARE, 1440L)
        attempt.markEnd(TraceStage.PREPARE, 1536L)
        attempt.markMeasured(TraceStage.DECODE, 210L)
        attempt.markPoint(TraceStage.FIRST_DECODE, 1624L, approximate = true)
        attempt.markPoint(TraceStage.FIRST_FRAME, 1636L)
        attempt.noteFilterDownload(320L, false)
        val finished = attempt.finish(TraceOutcome.SUCCESS, 1636L)
        val snap = finished.copy(sessionId = 7L, attemptIndex = 2)
        val line = formatLogLine(snap)
        assertTrue(line.startsWith("首屏诊断 会话#7 尝试#2 点播 MEDIA3 "))
        assertTrue(line.contains("解析=412ms"))
        assertTrue(line.contains("DNS=未取到"))
        assertTrue(line.contains("建连=未取到"))
        assertTrue(line.contains("开流=380ms"))
        assertTrue(line.contains("媒体准备=96ms"))
        assertTrue(line.contains("解码=210ms"))
        assertTrue(line.contains("首帧解码=88ms（近似）"))
        assertTrue(line.contains("首帧渲染=12ms"))
        assertTrue(line.contains("总计=636ms"))
        assertTrue(line.contains("（过滤下载=320ms）"))
    }

    @Test fun logLineFailedTotalIsUnfinished() {
        val clock = FakeClock(1000L)
        val attempt = FirstScreenAttempt(1L, "LIBMPV", 1000L, true, clock.fn)
        attempt.markMissing(TraceStage.DNS, TraceMissing.UNAVAILABLE)
        val snap = attempt.finish(TraceOutcome.FAILED, 2000L).copy(sessionId = 1L, attemptIndex = 1)
        val line = formatLogLine(snap)
        assertTrue(line.contains("直播 LIBMPV"))
        assertTrue(line.contains("总计=未完成"))
        assertTrue(line.contains(" 失败"))
    }

    @Test fun overlayLinesMinimumSet() {
        val clock = FakeClock(1000L)
        val attempt = fullAttempt(clock)
        val snap = attempt.finish(TraceOutcome.SUCCESS, 1846L).copy(sessionId = 7L, attemptIndex = 1)
        val lines = formatOverlayLines(snap)
        assertEquals("首屏诊断 · 点播 · MEDIA3", lines[0])
        assertTrue(lines.any { it.startsWith("DNS：20ms") })
        assertTrue(lines.any { it.startsWith("总计：846ms") })
    }

    @Test fun firstMarkWinsAndFinishIsIdempotent() {
        val clock = FakeClock(1000L)
        val attempt = FirstScreenAttempt(1L, "MEDIA3", 1000L, false, clock.fn)
        attempt.markStart(TraceStage.DNS, 1010L)
        attempt.markStart(TraceStage.DNS, 1050L)
        attempt.markEnd(TraceStage.DNS, 1020L)
        attempt.markEnd(TraceStage.DNS, 1090L)
        assertEquals(10L, attempt.snapshot(1100L).durations[TraceStage.DNS])
        val first = attempt.finish(TraceOutcome.SUCCESS, 1100L)
        val second = attempt.finish(TraceOutcome.FAILED, 1200L)
        assertEquals(TraceOutcome.SUCCESS, second.outcome)
        assertEquals(first, second)
        // 终结后打点不再生效。
        attempt.markStart(TraceStage.CONNECT, 1110L)
        assertNull(attempt.snapshot(1200L).durations[TraceStage.CONNECT])
    }

    @Test fun filterDownloadCacheHitRecorded() {
        val clock = FakeClock(1000L)
        val attempt = FirstScreenAttempt(1L, "LIBMPV", 1000L, false, clock.fn)
        attempt.noteFilterDownload(0L, true)
        val snap = attempt.snapshot(1100L)
        assertEquals(0L, snap.filterDownloadMs)
        assertTrue(snap.filterCacheHit)
        val line = formatLogLine(snap.copy(sessionId = 1L, attemptIndex = 1))
        assertTrue(line.contains("（过滤下载=0ms，缓存命中）"))
    }
}
