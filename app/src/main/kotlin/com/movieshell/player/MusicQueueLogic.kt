package com.movieshell.player

/**
 * v0.294.17：切歌下标计算的纯逻辑（无 Android 依赖，单测覆盖）。
 *
 * 规则：
 * - 用户点「下一首」：列表/单曲循环都按顺序 +1（单曲循环只管自然播完），随机模式随机另一首；
 * - 用户点「上一首」：已播超过 [PREV_RESTART_THRESHOLD_MS] 先回到本首开头（通用约定）；
 *   否则随机模式优先回到随机历史里的上一首，再按顺序 -1；
 * - 自然播完：单曲循环=本首；列表循环=+1；随机=随机另一首；
 * - 解析失败自动跳过：同方向继续找，最多把整个队列试一圈（[maxSkipAttempts]），不死循环。
 */
internal object MusicQueueLogic {
    const val PREV_RESTART_THRESHOLD_MS = 3_000L

    enum class Mode { ALL, ONE, SHUFFLE }

    /** 用户手动下一首。 */
    fun nextIndex(size: Int, current: Int, mode: Mode, random: (Int) -> Int): Int {
        if (size <= 0) return -1
        if (current !in 0 until size) return 0
        return if (mode == Mode.SHUFFLE && size > 1) randomOther(size, current, random)
        else (current + 1) % size
    }

    /** 用户手动上一首（不含「回到本首开头」判断，见 [shouldRestartOnPrev]）。 */
    fun prevIndex(size: Int, current: Int, mode: Mode, shuffleHistory: List<Int>): Int {
        if (size <= 0) return -1
        if (current !in 0 until size) return size - 1
        if (mode == Mode.SHUFFLE) {
            val back = shuffleHistory.lastOrNull { it in 0 until size && it != current }
            if (back != null) return back
        }
        return (current - 1 + size) % size
    }

    /** 自然播完后的下一首下标。 */
    fun autoNextIndex(size: Int, current: Int, mode: Mode, random: (Int) -> Int): Int {
        if (size <= 0) return -1
        if (current !in 0 until size) return 0
        return when (mode) {
            Mode.ONE -> current
            Mode.ALL -> (current + 1) % size
            Mode.SHUFFLE -> if (size > 1) randomOther(size, current, random) else current
        }
    }

    /** 上一首：已播超过 3 秒且当前曲目确实在放（不是解析中）时回到开头。 */
    fun shouldRestartOnPrev(positionMs: Long, trackLoaded: Boolean): Boolean =
        trackLoaded && positionMs > PREV_RESTART_THRESHOLD_MS

    /** 解析失败后同方向的下一个尝试下标（direction=+1/-1）。 */
    fun skipIndex(size: Int, failed: Int, direction: Int): Int {
        if (size <= 0) return -1
        val d = if (direction < 0) -1 else 1
        return ((failed + d) % size + size) % size
    }

    /** 一次切歌链最多自动跳过几首：整队其余各首都试一遍。 */
    fun maxSkipAttempts(size: Int): Int = (size - 1).coerceAtLeast(0)

    private fun randomOther(size: Int, current: Int, random: (Int) -> Int): Int {
        // 从除当前外的 size-1 首里均匀取一首，不用 while 重抽（不依赖随机源收敛）
        val r = random(size - 1).coerceIn(0, size - 2)
        return if (r >= current) r + 1 else r
    }
}
