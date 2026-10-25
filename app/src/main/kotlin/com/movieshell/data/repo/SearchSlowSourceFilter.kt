package com.movieshell.data.repo

import java.util.concurrent.ConcurrentHashMap

/**
 * v0.162.0：搜索慢源会话黑名单。
 *
 * 生命周期 = 一次 App 会话（进程内单例），不持久化——
 * 慢源下次启动还有机会，避免临时慢站被永久埋掉。
 * 只计超时：普通失败（5xx、空结果）不计，成功清零。
 * 黑名单一旦命中，本会话内不再发请求（onSuccess 不会再被调到，
 * 所以黑名单在本会话内是粘性的，这是设计，不是 bug）。
 * v0.217.0（F67）：计次容器换线程安全实现——原来普通 HashMap＋
 * mutableSet，聚合搜索多源并行读写同一张表，轻则计数丢几次、该
 * 拉黑的没拉黑，重则并发改表结构出问题；计次用 merge 原子累加。
 */
object SearchSlowSourceFilter {

    private const val BLACKLIST_AFTER = 3

    private val failCount = ConcurrentHashMap<String, Int>()
    private val blacklisted = ConcurrentHashMap.newKeySet<String>()

    /** 最近一次 searchAll 因黑名单跳过的源数（供结果页提示用）。 */
    @Volatile
    var lastSkipped: Int = 0
        private set

    fun shouldSkip(siteKey: String): Boolean = siteKey in blacklisted

    fun onTimeout(siteKey: String) {
        val n = failCount.merge(siteKey, 1, Int::plus) ?: 1
        if (n >= BLACKLIST_AFTER) blacklisted += siteKey
    }

    fun onSuccess(siteKey: String) {
        failCount[siteKey] = 0
    }

    fun setLastSkipped(n: Int) {
        lastSkipped = n
    }

    /** v0.163.0：手动清除（结果页“重试被跳过的源”用），清掉黑名单和计次。 */
    fun clear() {
        failCount.clear()
        blacklisted.clear()
        lastSkipped = 0
    }

    fun skippedLabel(): String? =
        if (lastSkipped > 0) "$lastSkipped 个源响应慢已跳过" else null
}
