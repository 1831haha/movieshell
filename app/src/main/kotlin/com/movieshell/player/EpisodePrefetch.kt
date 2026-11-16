package com.movieshell.player

/**
 * v1.2.14 B片（2）：下一集后台预解析——纯函数部分。
 *
 * 预解析只做"下一集"（窗口为 1 集）：当前集播着时，后台悄悄解析下一集的
 * 播放地址并缓存；切集时命中缓存直接用，省掉一次现解析的等待。解析失败
 * 只记日志、不弹错、不影响当前播放（quiet 解析见 PlayResolver）。
 * 缓存带 10 分钟有效期——签过名的直链会过期，过期缓存宁可丢弃重解析，
 * 也不拿过期地址去播黑屏。
 */
object EpisodePrefetch {

    /** 预解析在起播后等这么久再启动：让当前集先出画面，不跟起播抢资源。 */
    const val START_DELAY_MS = 2000L

    /** 预解析结果的有效期（毫秒）：超过即视为过期，切集时重新解析。 */
    const val MAX_AGE_MS = 10 * 60 * 1000L

    /** 预解析结果：缓存的下一集地址。 */
    data class PrefetchedEpisode(
        val lineIndex: Int,
        val episodeIndex: Int,
        val url: String,
        val prefetchedAtMs: Long,
    )

    /**
     * 算出该预解析哪一集：同线路下一集；没有下一集（最后一集/直播/无选集）
     * 返回 null。只预解析下一集，不往更远处预取——解析是重活（规则执行、
     * 解析线路竞速），一次一集是成本与收益的平衡点。
     */
    fun nextEpisodeToPrefetch(
        lineIndex: Int,
        episodeIndex: Int,
        episodeCount: Int,
        isLive: Boolean,
    ): Pair<Int, Int>? {
        if (isLive) return null
        if (episodeCount <= 0) return null
        val next = episodeIndex + 1
        if (next >= episodeCount) return null
        return lineIndex to next
    }

    /**
     * 缓存能不能直接用：集号对得上、地址非空、没过期。
     * 集号对不上（切了线路/跳集）或过期都算不可用，切集时走正常解析。
     */
    fun isPrefetchUsable(
        cached: PrefetchedEpisode?,
        lineIndex: Int,
        episodeIndex: Int,
        nowMs: Long,
    ): Boolean {
        if (cached == null) return false
        if (cached.lineIndex != lineIndex || cached.episodeIndex != episodeIndex) return false
        if (cached.url.isBlank()) return false
        if (nowMs - cached.prefetchedAtMs > MAX_AGE_MS) return false
        return true
    }
}
