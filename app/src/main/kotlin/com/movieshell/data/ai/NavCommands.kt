package com.movieshell.data.ai

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * v1.3.25 合并 3（AI Agent 工具化操控）：导航命令通道。
 *
 * NavController 只活在 MainActivity 的组合作用域里，AppActionBus
 * 够不着；导航命令经这里解耦——Bus 只管发，MainActivity 订阅后执行。
 * 目前只有搜片一类（切搜索 tab，片名再经 SearchQueryBus 递给搜索页）。
 *
 * v1.3.25 审查修：与 PlayerCommands / SearchQueryBus 同款改成容量 1
 * 的 Channel（新命令顶掉旧的）——SharedFlow replay=0 时订阅者没到场
 * 命令就丢了，Channel 里等订阅者来取，取过一次即消费。
 */
object NavCommands {
    sealed interface Command {
        data class Search(val query: String) : Command
    }

    private val _commands = Channel<Command>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val commands: Flow<Command> = _commands.receiveAsFlow()

    fun post(command: Command): Boolean = _commands.trySend(command).isSuccess
}
