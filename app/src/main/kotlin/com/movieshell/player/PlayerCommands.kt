package com.movieshell.player

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * v1.3.25 合并 3（AI Agent 工具化操控）：播放器命令通道。
 *
 * 播放器状态（当前线路/集、倍速）只活在 PlayerScreen 的 composable
 * 作用域里，AppActionBus 够不着；命令经这里解耦——Bus 只管发，
 * PlayerScreen 订阅后在自己的作用域里执行（switchEpisode / applySpeed
 * 都是局部函数，调用点必须在声明之后，收集器见 PlayerScreen 末尾附近）。
 *
 * v1.3.25 审查修（GPT M-07）：原来是 replay=0 的 SharedFlow，
 * 订阅者没到场时发的命令直接丢了，Bus 还回 Ok 谎报成功。
 * 改成容量 1 的 Channel（新命令顶掉旧的）：命令在缓冲里等订阅者来取，
 * 取过一次即消费，重进页面不会重放。
 */
object PlayerCommands {
    sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data object NextEpisode : Command
        data object PrevEpisode : Command
        data class SeekTo(val ms: Long) : Command
        data class SetSpeed(val speed: Float) : Command
    }

    private val _commands = Channel<Command>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val commands: Flow<Command> = _commands.receiveAsFlow()

    fun post(command: Command): Boolean = _commands.trySend(command).isSuccess
}
