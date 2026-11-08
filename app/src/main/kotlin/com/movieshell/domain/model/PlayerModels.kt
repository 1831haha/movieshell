package com.movieshell.domain.model

/**
 * v1.1.7 分层第 3 批：从 data/repo/PlayerSettings.kt 一字未改移入，只换包。
 * 播放器内核枚举与外挂字幕样式是纯模型（无 Android 依赖），播放器组（player/）
 * 与设置页（ui/）双向共用，放 domain/model。
 */

/** v0.20.0：播放器内核（Media3 默认，libmpv 可选，记忆选择） */
enum class PlayerEngine { MEDIA3, LIBMPV }

/**
 * v0.21.0：外挂字幕样式（双引擎共用，存在 movieshell_prefs）。
 * - edge: 0=无 1=描边 2=阴影
 * - position: 0=底部 1=顶部
 * - offsetMs: 字幕时间偏移，±10000ms
 */
data class SubtitleStyle(
    val sizeSp: Int = 16,
    val color: Int = 0xFFFFFFFF.toInt(),
    val edge: Int = EDGE_OUTLINE,
    val position: Int = POS_BOTTOM,
    val offsetMs: Long = 0L,
) {
    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2
        const val POS_BOTTOM = 0
        const val POS_TOP = 1
    }
}
