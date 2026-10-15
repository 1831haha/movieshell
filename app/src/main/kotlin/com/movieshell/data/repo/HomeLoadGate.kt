package com.movieshell.data.repo

import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.ModelVod
import kotlinx.coroutines.Deferred

/**
 * 首页加载单飞门（v0.31.0）。
 *
 * 根因：v0.29.0 的"连续 3 次失败退避"是计数补丁——计数器放在 HomeScreen 的 remember
 * 里（按 composition 实例维度），多个并发/重建的实例各自计数、各自触发，退避形同
 * 虚设（13:05 日志同一秒"连续 1 次"刷到"连续 8 次"）。
 *
 * 修法：
 * - 单飞锁：同一数据源 key 同时只允许一次在途请求，后到的 await 同一个 Deferred，
 *   复用执行方算好的结果，而不是各跑各的；
 * - 失败计数按 key 统一记在单例里，只有真正执行的那一次才计数，复用方不重复记；
 * - 退避沿用 v0.29.0 语义（非手动触发连续 3 次全失败后跳过），挂在统一计数上。
 */
object HomeLoadGate {

    /** 单飞共享的首页加载结果：执行方算好，复用方直接应用到自己的状态。 */
    data class HomePayload(
        /** 最终推荐列表；null=网络失败且无缓存（apply 时保持现状） */
        val vods: List<ModelVod>?,
        /** 最终分类列表 */
        val cats: List<CategoryType>,
        /** 分类失败且无任何分类可展示（UI 给重试入口） */
        val catsFailed: Boolean,
        /** 网络成功时重置选中（一级分类回到"全部"） */
        val resetSelection: Boolean,
    )

    private val lock = Any()
    private val flights = mutableMapOf<String, Deferred<HomePayload?>>()
    private val streaks = mutableMapOf<String, Int>()

    /** 取在途请求（null=无在途）。调用方 await 它复用结果。 */
    fun flight(key: String): Deferred<HomePayload?>? = synchronized(lock) {
        flights[key]?.takeIf { it.isActive }
    }

    /**
     * 登记一次新的在途请求。返回 false 表示已有在途（调用方应改为复用，
     * 并 cancel 掉自己刚建的 deferred，避免泄漏）。
     */
    fun start(key: String, deferred: Deferred<HomePayload?>): Boolean = synchronized(lock) {
        val cur = flights[key]
        if (cur != null && cur.isActive) return false
        flights[key] = deferred
        true
    }

    /**
     * 在途请求结束：清理登记；只有真正执行的那一次才记失败/清零，
     * ok=有数据可展示（沿用"全失败且无缓存"语义）。
     */
    fun finish(key: String, deferred: Deferred<HomePayload?>, ok: Boolean) = synchronized(lock) {
        if (flights[key] === deferred) flights.remove(key)
        streaks[key] = if (ok) 0 else (streaks[key] ?: 0) + 1
    }

    fun streak(key: String): Int = synchronized(lock) { streaks[key] ?: 0 }
}
