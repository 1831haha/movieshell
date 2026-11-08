package com.movieshell.core.net

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * v1.1.6 反爬吸收项：同域限速 + 403/429 指数退避（只做节奏控制，不建客户端）。
 *
 * - 间隔只对配了 concurrentRate 的源生效（默认 0，不拖慢正常源；小说正文
 *   连续翻页不受影响）；
 * - 退避只在 403/429 后触发，下一次同域请求自动拉长间隔，最长 30 秒；
 * - 调用点：BookRuleEngine 的两个出网口（fetchPageOutcome / fetchSearchPageOutcome）
 *   与 V8DrpyEngine.doRequest（只挂退避钩子，不改客户端与预算逻辑）。
 * 间隔按源配，不按请求配——源入口（NovelSourceEngine）调 setGap，执行层只读表。
 */
object SourceThrottle {

    private val gaps = ConcurrentHashMap<String, Long>()
    private val lastHit = ConcurrentHashMap<String, Long>()
    private val penalty = ConcurrentHashMap<String, Long>()

    /** 源入口调一次：该 host 的请求间隔（毫秒），0 表示不设间隔。 */
    fun setGap(host: String, gapMs: Long) {
        if (host.isBlank()) return
        if (gapMs > 0) gaps[host] = gapMs else gaps.remove(host)
    }

    /** 出网口调一次：同域串行 + 随机抖动；被罚过则拉长间隔。 */
    suspend fun throttle(host: String) {
        if (host.isBlank()) return
        // 每次调用都记时间（即使本次不延迟），否则 403 刚罚完、上一条记录却是
        // 很久以前的，退避会被"已过去很久"抵消掉、等于没罚。
        val now = System.currentTimeMillis()
        val prev = synchronized(lastHit) { lastHit.put(host, now) ?: 0L }
        val gap = gaps[host] ?: 0L
        val extra = penalty[host] ?: 0L
        if (gap <= 0 && extra <= 0) return
        val wait = gap + Random.nextLong(0, 500) + extra - (now - prev)
        if (wait > 0) delay(wait)
    }

    /** 命中 403/429：指数退避，最长 30 秒；成功时由 clearPenalty 清掉。 */
    fun penalize(host: String) {
        if (host.isBlank()) return
        penalty[host] = ((penalty[host] ?: 1000L) * 2).coerceAtMost(30_000L)
    }

    fun clearPenalty(host: String) {
        if (host.isBlank()) return
        penalty.remove(host)
    }

    /** 解析书源 concurrentRate："1000" 或 "2/1000"（2 次/1000 毫秒）→ 每次间隔毫秒；非法回 0。 */
    fun parseConcurrentRate(rate: String?): Long {
        if (rate.isNullOrBlank()) return 0L
        val t = rate.trim()
        return if (t.contains("/")) {
            val parts = t.split("/")
            val count = parts.getOrNull(0)?.toLongOrNull() ?: 1L
            val ms = parts.getOrNull(1)?.toLongOrNull() ?: 1000L
            if (count <= 0) 0L else ms / count
        } else {
            t.toLongOrNull() ?: 0L
        }
    }
}
