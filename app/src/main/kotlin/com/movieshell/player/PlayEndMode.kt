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
    fun action(mode: Int, episodeIndex: Int, episodeCount: Int): PlayEndAction {
        if (episodeCount <= 0) return PlayEndAction.Pause
        val last = episodeCount - 1
        val next = episodeIndex + 1
        return when (mode) {
            1 -> PlayEndAction.ReplayCurrent
            2 -> if (next <= last) PlayEndAction.PlayNext(next) else PlayEndAction.PlayNext(0)
            3 -> PlayEndAction.Pause
            else -> if (next <= last) PlayEndAction.PlayNext(next) else PlayEndAction.Pause
        }
    }

    /**
     * v1.3.21 H16：片尾触发点（毫秒）——纯函数，可单测。
     *
     * 时长 < 5000ms 是塌掉的分母：真机实测分片展开后 mpv 只认单分片时长，
     * 约 4 秒；阈值 5000 盖住 4 秒并留 1 秒余量，此时永不触发，返回
     * Long.MAX_VALUE。真实短视频（<5 秒）此路也不再自动触发片尾动作，
     * 播完仍走 mpv/Media3 自身的 END_FILE 路径，影响有限。
     * 有片尾跳过且跳过量小于总时长时，触发点 = 时长 − 跳过量；否则
     * 触发点 = 时长 − 1000ms（留 1 秒余量，防时长抖动）。
     */
    fun computeEndAtMs(durationMs: Long, skipOutroSec: Int): Long {
        if (durationMs < 5000L) return Long.MAX_VALUE
        val skipMs = skipOutroSec.coerceAtLeast(0) * 1000L
        return if (skipMs > 0 && skipMs < durationMs) durationMs - skipMs else durationMs - 1000L
    }
}
