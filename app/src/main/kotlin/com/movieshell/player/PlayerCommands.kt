package com.movieshell.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * v1.3.25 合并 3（AI Agent 工具化操控）：播放器命令通道。
 *
 * 播放器状态（当前线路/集、倍速）只活在 PlayerScreen 的 composable
 * 作用域里，AppActionBus 够不着；命令经这里解耦——Bus 只管发，
 * PlayerScreen 订阅后在自己的作用域里执行（switchEpisode / applySpeed
 * 都是局部函数，调用点必须在声明之后，收集器见 PlayerScreen 末尾附近）。
 * 播放器不在前台时命令只进容量 1 的缓冲，满了返回 false，不排队积压。
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

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 1)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    fun post(command: Command): Boolean = _commands.tryEmit(command)
}
