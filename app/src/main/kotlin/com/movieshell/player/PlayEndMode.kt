package com.movieshell.player

/**
 * v1.2.14 B片（8）：播完行为——纯函数部分。
 *
 * 循环模式下标与 PlayerScreen.PLAY_MODES 顺序一致：
 * 0=顺序播放 1=单集循环 2=列表循环 3=播完停止（本版新增）。
 * 定时关闭优先级最高，不在这里判定——调用方先查 VideoSleepTimer，
 * 再按这里的结果执行。
 */
object PlayEndMode {

    /** 播完后做什么（调用方据此执行，纯决策不碰播放器）。 */
    sealed interface PlayEndAction {
        /** 停住（播完停止，或顺序播放已到最后一集）。 */
        data object Pause : PlayEndAction

        /** 回到本集开头重播（单集循环）。 */
        data object ReplayCurrent : PlayEndAction

        /** 切到指定集。 */
        data class PlayNext(val episodeIndex: Int) : PlayEndAction
    }

    /**
     * 按模式与当前集位置决策播完行为。
     *
     * @param mode 循环模式下标（0 顺序 / 1 单集循环 / 2 列表循环 / 3 播完停止）
     * @param episodeIndex 当前集下标
     * @param episodeCount 当前线路总集数
     */
    /**
     * v1.3.21 H16：片尾触发点（毫秒）——纯函数，可单测。
     *
     * 时长 < 2000ms 是塌掉的分母（分片展开后 mpv 只认单分片时长，约 4 秒；
     * 更短的更是脏数据），此时永不触发，返回 Long.MAX_VALUE。
     * 有片尾跳过且跳过量小于总时长时，触发点 = 时长 − 跳过量；否则
     * 触发点 = 时长 − 1000ms（留 1 秒余量，防时长抖动）。
     */
    fun computeEndAtMs(durationMs: Long, skipOutroSec: Int): Long {
        if (durationMs < 2000L) return Long.MAX_VALUE
        val skipMs = skipOutroSec.coerceAtLeast(0) * 1000L
        return if (skipMs > 0 && skipMs < durationMs) durationMs - skipMs else durationMs - 1000L
    }
}
