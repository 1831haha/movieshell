package com.movieshell.ui.search

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * v1.3.25 合并 3（AI Agent 工具化操控）：搜片命令通道。
 *
 * SearchViewModel 是 Activity 级的，AppActionBus 拿不到实例；片名经
 * 这里解耦——Bus 只管发，SearchScreen 订阅后填框并直接搜。
 * 搜索页不在前台时片名只进容量 1 的缓冲，满了返回 false，不排队积压。
 */
object SearchQueryBus {
    private val _queries = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val queries: SharedFlow<String> = _queries.asSharedFlow()

    fun post(query: String): Boolean = _queries.tryEmit(query)
}
