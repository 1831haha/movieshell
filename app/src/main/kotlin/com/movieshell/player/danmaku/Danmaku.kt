package com.movieshell.player.danmaku

/**
 * v0.54：弹幕数据模型。
 * timeMs = 在视频第几毫秒出现；type 0=滚动 1=顶部固定 2=底部固定。
 * （用户明确：弹幕发送功能永远不做，只看不发）
 */
data class Danmaku(
    val text: String,
    val timeMs: Long,
    val color: Int = 0xFFFFFFFF.toInt(),
    val type: Int = TYPE_SCROLL,
) {
    companion object {
        const val TYPE_SCROLL = 0
        const val TYPE_TOP = 1
        const val TYPE_BOTTOM = 2
    }
}
