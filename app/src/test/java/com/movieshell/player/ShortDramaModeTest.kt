package com.movieshell.player

import org.junit.Assert.*
import org.junit.Test

/**
 * v1.4.8 A7：短剧模式开关状态机单测（定稿 §7.5）。
 * 方向值只当不透明整数存取（纯逻辑不解读它），与 Activity 的
 * requestedOrientation 原值一一对应、退模式时原样恢复。
 */
class ShortDramaModeTest {
    @Test fun visibleOnlyForVodWithMultipleEpisodes() {
        assertTrue(ShortDramaState.isVisible(isLive = false, episodeCount = 2))
        assertTrue(ShortDramaState.isVisible(isLive = false, episodeCount = 30))
        assertFalse(ShortDramaState.isVisible(isLive = false, episodeCount = 1))
        assertFalse(ShortDramaState.isVisible(isLive = false, episodeCount = 0))
        assertFalse(ShortDramaState.isVisible(isLive = true, episodeCount = 5))
    }

    @Test fun enterStoresSnapshotAndTurnsOn() {
        val snapshot = DramaOrientationSnapshot(requestedOrientation = 0, manualLocked = true)
        val entered = ShortDramaState().enter(snapshot)
        assertTrue(entered.isOn)
        assertEquals(snapshot, entered.snapshot)
    }

    @Test fun enterTwiceKeepsFirstSnapshot() {
        val first = DramaOrientationSnapshot(requestedOrientation = 0, manualLocked = true)
        val second = DramaOrientationSnapshot(requestedOrientation = 1, manualLocked = false)
        val entered = ShortDramaState().enter(first).enter(second)
        assertTrue(entered.isOn)
        assertEquals(first, entered.snapshot)
    }

    @Test fun exitReturnsSnapshotAndTurnsOff() {
        val snapshot = DramaOrientationSnapshot(requestedOrientation = 1, manualLocked = false)
        val (exited, restored) = ShortDramaState().enter(snapshot).exit()
        assertFalse(exited.isOn)
        assertNull(exited.snapshot)
        assertEquals(snapshot, restored)
    }

    @Test fun exitWhenOffReturnsNullAndKeepsState() {
        val off = ShortDramaState()
        val (same, restored) = off.exit()
        assertEquals(off, same)
        assertNull(restored)
    }

    @Test fun reenterAfterExitReplacesSnapshot() {
        val first = DramaOrientationSnapshot(requestedOrientation = 0, manualLocked = true)
        val second = DramaOrientationSnapshot(requestedOrientation = -1, manualLocked = false)
        val (exited, _) = ShortDramaState().enter(first).exit()
        val reentered = exited.enter(second)
        assertTrue(reentered.isOn)
        assertEquals(second, reentered.snapshot)
    }
}
