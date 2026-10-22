package com.movieshell.data.repo

/**
 * v0.162.0：搜索慢源会话黑名单。
 *
 * 生命周期 = 一次 App 会话（进程内单例），不持久化——
 * 慢源下次启动还有机会，避免临时慢站被永久埋掉。
 * 只计超时：普通失败（5xx、空结果）不计，成功清零。
 * 黑名单一旦命中，本会话内不再发请求（onSuccess 不会再被调到，
 * 所以黑名单在本会话内是粘性的，这是设计，不是 bug）。
 */
object SearchSlowSourceFilter {

    private const val BLACKLIST_AFTER = 3

    private val failCount = HashMap<String, Int>()
    private val blacklisted = mutableSetOf<String>()

    /** 最近一次 searchAll 因黑名单跳过的源数（供结果页提示用）。 */
    var lastSkipped: Int = 0
        private set

    fun shouldSkip(siteKey: String): Boolean = siteKey in blacklisted

    fun onTimeout(siteKey: String) {
        val n = (failCount[siteKey] ?: 0) + 1
        failCount[siteKey] = n
        if (n >= BLACKLIST_AFTER) blacklisted += siteKey
    }

    fun onSuccess(siteKey: String) {
        failCount[siteKey] = 0
    }

    fun setLastSkipped(n: Int) {
        lastSkipped = n
    }

    fun skippedLabel(): String? =
        if (lastSkipped > 0) "$lastSkipped 个源响应慢已跳过" else null
}
