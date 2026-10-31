package com.movieshell.ui.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayerNavTest {
    @Test fun alreadyOnPlayerDoesNothing() {
        assertEquals(PlayerNavAction.NONE, decideOpenPlayer(MUSIC_PLAYER_ROUTE, playerInBackStack = true))
    }

    @Test fun playerBelowCurrentPopsBackInsteadOfStacking() {
        // 播放页 → 换源 → 音源管理 → 点迷你条：弹回原播放页，不再叠第二层
        assertEquals(PlayerNavAction.POP_TO_EXISTING, decideOpenPlayer("music_source", playerInBackStack = true))
    }

    @Test fun fromMusicTabNavigatesOnce() {
        assertEquals(PlayerNavAction.NAVIGATE, decideOpenPlayer("music", playerInBackStack = false))
        assertEquals(PlayerNavAction.NAVIGATE, decideOpenPlayer(null, playerInBackStack = false))
    }

    @Test fun backPopsOnlyWhenOnPlayer() {
        assertTrue(shouldPopPlayer(MUSIC_PLAYER_ROUTE))
        // 第二次点返回箭头时当前已是音乐页：不再弹（原来会把音乐页也弹掉）
        assertFalse(shouldPopPlayer("music"))
        assertFalse(shouldPopPlayer(null))
    }
}
