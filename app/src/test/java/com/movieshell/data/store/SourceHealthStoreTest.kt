package com.movieshell.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.1.31：站点健康分纯逻辑测试（分数公式、选源 id 映射、播放记账节流）。 */
class SourceHealthStoreTest {

    @Test
    fun `new source starts at 50`() {
        assertEquals(50, SourceHealthStore.score("test-new-key-001"))
    }

    @Test
    fun `score rises with successes`() {
        repeat(9) { SourceHealthStore.recordSuccess("test-good-key-002") }
        assertEquals(90, SourceHealthStore.score("test-good-key-002"))
    }

    @Test
    fun `score falls with failures`() {
        repeat(9) { SourceHealthStore.recordFailure("test-bad-key-003") }
        assertEquals(9, SourceHealthStore.score("test-bad-key-003"))
    }

    @Test
    fun `one success beats fifty fifty`() {
        SourceHealthStore.recordSuccess("test-one-key-004")
        assertEquals(66, SourceHealthStore.score("test-one-key-004"))
    }

    @Test
    fun `picker id maps to site key`() {
        repeat(9) { SourceHealthStore.recordSuccess("test-picker-key-005") }
        assertEquals(90, SourceHealthStore.scoreForPickerId("sub:https://example.com/sub.json:test-picker-key-005"))
        assertEquals(50, SourceHealthStore.scoreForPickerId("local:drpy:test-picker-key-005"))
    }

    @Test
    fun `play success once per url within throttle`() {
        SourceHealthStore.recordPlaySuccessOnce("http://example.com/play-006.m3u8", "test-play-key-006")
        SourceHealthStore.recordPlaySuccessOnce("http://example.com/play-006.m3u8", "test-play-key-006")
        assertEquals(66, SourceHealthStore.score("test-play-key-006"))
    }

    @Test
    fun `play failure after success on same url is throttled`() {
        SourceHealthStore.recordPlaySuccessOnce("http://example.com/play-007.m3u8", "test-play-key-007")
        SourceHealthStore.recordPlayFailureOnce("http://example.com/play-007.m3u8", "test-play-key-007")
        assertEquals(66, SourceHealthStore.score("test-play-key-007"))
    }
}
