package com.movieshell.player

import com.movieshell.data.ai.NavCommands
import com.movieshell.ui.search.SearchQueryBus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.25 审查修（GPT M-06/M-07）：三条命令通道从 replay=0 的 SharedFlow
 * 改成容量 1 的 Channel——订阅者没到场时发的命令不能丢，取过一次即消费，
 * 重订阅不重放，新命令顶掉旧的。
 */
class CommandBusTest {

    @Test
    fun playerCommands_postBeforeCollect_deliversOnce() = runBlocking {
        assertTrue(PlayerCommands.post(PlayerCommands.Command.Pause))
        val got = withTimeoutOrNull(1000) { PlayerCommands.commands.first() }
        assertEquals(PlayerCommands.Command.Pause, got)
        assertNull(withTimeoutOrNull(200) { PlayerCommands.commands.first() })
    }

    @Test
    fun playerCommands_newestWins() = runBlocking {
        PlayerCommands.post(PlayerCommands.Command.Pause)
        PlayerCommands.post(PlayerCommands.Command.Resume)
        val got = withTimeoutOrNull(1000) { PlayerCommands.commands.first() }
        assertEquals(PlayerCommands.Command.Resume, got)
    }

    @Test
    fun searchQueryBus_postBeforeCollect_deliversOnce() = runBlocking {
        assertTrue(SearchQueryBus.post("谷雨"))
        val got = withTimeoutOrNull(1000) { SearchQueryBus.queries.first() }
        assertEquals("谷雨", got)
        assertNull(withTimeoutOrNull(200) { SearchQueryBus.queries.first() })
    }

    @Test
    fun navCommands_postBeforeCollect_deliversOnce() = runBlocking {
        assertTrue(NavCommands.post(NavCommands.Command.Search("谷雨")))
        val got = withTimeoutOrNull(1000) { NavCommands.commands.first() }
        assertEquals(NavCommands.Command.Search("谷雨"), got)
        assertNull(withTimeoutOrNull(200) { NavCommands.commands.first() })
    }
}
