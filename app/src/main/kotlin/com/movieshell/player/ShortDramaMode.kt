package com.movieshell.player

/**
 * v1.4.8 A7：短剧模式纯逻辑（可单测，不依赖 Android/Compose）。
 *
 * 模式定义（定稿 §7.2）：短剧模式 = 竖屏沉浸播放形态，由四条组成，
 * 不发明第五条——竖屏全屏、上下滑切集、精简控制层、播完自动下一集。
 * 后三条现状已有（EpisodeSwipe + swipeToEpisode、竖屏精简条、
 * 片尾跳过 + 自然播完自动下一集），这里只管“打包成一个开关”的
 * 状态机与入口可见性；锁竖屏、强制全屏、方向快照的动作在
 * PlayerScreen 里按定稿顺序调，不进本文件。
 */
data class DramaOrientationSnapshot(
    /** 进模式前 Activity.requestedOrientation 的原值 */
    val requestedOrientation: Int,
    /** 进模式前 OrientationSensor.manualLocked 的原值 */
    val manualLocked: Boolean,
)

/**
 * 短剧模式状态：进页默认关，不持久化。快照只在模式开着时有值，
 * 退模式时调用方凭 exit() 返回的快照恢复方向，再无快照可恢复。
 */
data class ShortDramaState(
    val isOn: Boolean = false,
    val snapshot: DramaOrientationSnapshot? = null,
) {
    companion object {
        /** 入口可见：仅点播、集数 > 1 时可见；直播不显示。 */
        fun isVisible(isLive: Boolean, episodeCount: Int): Boolean =
            !isLive && episodeCount > 1
    }

    /** 进模式：已开时原样返回，不覆盖已有快照。 */
    fun enter(snapshot: DramaOrientationSnapshot): ShortDramaState =
        if (isOn) this else copy(isOn = true, snapshot = snapshot)

    /**
     * 退模式：返回新状态与待恢复的方向快照。未开时返回原状态与 null，
     * 调用方什么都不恢复、不调退出目标。
     */
    fun exit(): Pair<ShortDramaState, DramaOrientationSnapshot?> =
        if (!isOn) this to null
        else copy(isOn = false, snapshot = null) to snapshot
}
