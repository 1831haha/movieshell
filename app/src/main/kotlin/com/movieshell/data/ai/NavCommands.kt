package com.movieshell.data.ai

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * v1.3.25 合并 3（AI Agent 工具化操控）：导航命令通道。
 *
 * NavController 只活在 MainActivity 的组合作用域里，AppActionBus
 * 够不着；导航命令经这里解耦——Bus 只管发，MainActivity 订阅后执行。
 * 目前只有搜片一类（切搜索 tab，片名再经 SearchQueryBus 递给搜索页）。
 */
object NavCommands {
    sealed interface Command {
        data class Search(val query: String) : Command
    }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 1)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    fun post(command: Command): Boolean = _commands.tryEmit(command)
}
