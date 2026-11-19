package com.movieshell.ui.search

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * v1.3.25 合并 3（AI Agent 工具化操控）：搜片命令通道。
 *
 * SearchViewModel 是 Activity 级的，AppActionBus 拿不到实例；片名经
 * 这里解耦——Bus 只管发，SearchScreen 订阅后填框并直接搜。
 *
 * v1.3.25 审查修（GPT M-06）：原来是 replay=0 的 SharedFlow，
 * 导航刚切过去、搜索页还没订阅时发的片名直接丢了。改成容量 1 的
 * Channel（新片名顶掉旧的）：片名在缓冲里等订阅者来取，取过一次即消费。
 */
object SearchQueryBus {
    private val _queries = Channel<String>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val queries: Flow<String> = _queries.receiveAsFlow()

    fun post(query: String): Boolean = _queries.trySend(query).isSuccess
}
