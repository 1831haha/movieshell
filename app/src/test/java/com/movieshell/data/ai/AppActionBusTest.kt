package com.movieshell.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.25 合并 3：AppActionBus 单测——工具说明、动作块解析、分发校验。
 * dispatch 里需要 UI 上下文的动作只走命令流（PlayerCommands /
 * NavCommands 的 tryEmit），纯 JUnit 下不碰 Android；switch_source
 * 不带 context 时直接回 Fail，不进管理器。
 */
class AppActionBusTest {

    @Test
    fun `toolsPrompt lists all eight actions`() {
        val prompt = AppActionBus.toolsPrompt()
        for (id in listOf("pause", "resume", "next_episode", "prev_episode", "seek", "set_speed", "search_video", "switch_source")) {
            assertTrue("prompt 缺动作 $id", prompt.contains(id))
        }
        assertTrue(prompt.contains("```action"))
    }

    @Test
    fun `parseActionCall extracts id and args`() {
        val text = "好的，马上暂停。\n```action\n{\"action\": \"seek\", \"args\": {\"seconds\": 90}}\n```"
        val call = AppActionBus.parseActionCall(text)
        assertEquals("seek", call?.id)
        assertEquals("90", call?.args?.get("seconds"))
    }

    @Test
    fun `parseActionCall returns null without block or with bad json`() {
        assertNull(AppActionBus.parseActionCall("暂停一下"))
        assertNull(AppActionBus.parseActionCall("```action\n{\"action\": \n```"))
        assertNull(AppActionBus.parseActionCall("```action\n{}\n```"))
        assertNull(AppActionBus.parseActionCall("```action\n{\"action\": \"pause\"}\n"))
    }

    @Test
    fun `dispatch pause posts to player commands`() = runTestBlocking {
        val r = AppActionBus.dispatch(null, "pause", emptyMap())
        assertTrue(r is AppActionBus.ActionResult.Ok)
        assertEquals("已暂停", r.summary)
    }

    @Test
    fun `dispatch unknown action fails`() = runTestBlocking {
        val r = AppActionBus.dispatch(null, "delete_everything", emptyMap())
        assertTrue(r is AppActionBus.ActionResult.Fail)
    }

    @Test
    fun `dispatch seek validates seconds`() = runTestBlocking {
        assertTrue(AppActionBus.dispatch(null, "seek", emptyMap()) is AppActionBus.ActionResult.Fail)
        assertTrue(AppActionBus.dispatch(null, "seek", mapOf("seconds" to "-5")) is AppActionBus.ActionResult.Fail)
        assertTrue(AppActionBus.dispatch(null, "seek", mapOf("seconds" to "abc")) is AppActionBus.ActionResult.Fail)
        val ok = AppActionBus.dispatch(null, "seek", mapOf("seconds" to "90"))
        assertTrue(ok is AppActionBus.ActionResult.Ok)
        assertEquals("已跳到 90 秒处", ok.summary)
    }

    // v1.3.25 审查修（GPT M-02）：秒数 *1000 溢出成负数前先拦；24 小时内放行。
    @Test
    fun `dispatch seek rejects absurd seconds`() = runTestBlocking {
        assertTrue(AppActionBus.dispatch(null, "seek", mapOf("seconds" to "100000")) is AppActionBus.ActionResult.Fail)
        val ok = AppActionBus.dispatch(null, "seek", mapOf("seconds" to "86400"))
        assertTrue(ok is AppActionBus.ActionResult.Ok)
        assertEquals("已跳到 86400 秒处", ok.summary)
    }

    @Test
    fun `dispatch set_speed validates range`() = runTestBlocking {
        assertTrue(AppActionBus.dispatch(null, "set_speed", mapOf("speed" to "0.1")) is AppActionBus.ActionResult.Fail)
        assertTrue(AppActionBus.dispatch(null, "set_speed", mapOf("speed" to "5")) is AppActionBus.ActionResult.Fail)
        assertTrue(AppActionBus.dispatch(null, "set_speed", mapOf("speed" to "x")) is AppActionBus.ActionResult.Fail)
        val ok = AppActionBus.dispatch(null, "set_speed", mapOf("speed" to "1.5"))
        assertTrue(ok is AppActionBus.ActionResult.Ok)
        assertEquals("倍速已设为 1.5X", ok.summary)
    }

    @Test
    fun `dispatch next_episode fails with no session`() = runTestBlocking {
        // 本仓无其他单测碰 PlaySession，默认 lines 为空，走“没有在播的剧集”分支。
        val r = AppActionBus.dispatch(null, "next_episode", emptyMap())
        assertTrue(r is AppActionBus.ActionResult.Fail)
        val p = AppActionBus.dispatch(null, "prev_episode", emptyMap())
        assertTrue(p is AppActionBus.ActionResult.Fail)
    }

    @Test
    fun `dispatch search_video validates query`() = runTestBlocking {
        assertTrue(AppActionBus.dispatch(null, "search_video", emptyMap()) is AppActionBus.ActionResult.Fail)
        val ok = AppActionBus.dispatch(null, "search_video", mapOf("query" to "谷雨"))
        assertTrue(ok is AppActionBus.ActionResult.Ok)
        assertEquals("正在搜「谷雨」", ok.summary)
    }

    @Test
    fun `dispatch switch_source without context fails`() = runTestBlocking {
        val r = AppActionBus.dispatch(null, "switch_source", mapOf("name" to "drpy"))
        assertTrue(r is AppActionBus.ActionResult.Fail)
    }

    @Test
    fun `dispatch switch_source without name fails`() = runTestBlocking {
        // 传一个非空 context 占位对象是不可能的（纯 JUnit 无 Context），
        // 这里只覆盖空名分支：context 为 null 时先回上下文缺失，同样是 Fail。
        val r = AppActionBus.dispatch(null, "switch_source", emptyMap())
        assertTrue(r is AppActionBus.ActionResult.Fail)
    }

    /**
     * dispatch 是 suspend：本仓单测无 kotlinx-coroutines-test，
     * 用 runBlocking 直跑。
     */
    private fun runTestBlocking(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
