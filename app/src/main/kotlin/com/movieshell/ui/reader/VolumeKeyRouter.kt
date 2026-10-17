package com.movieshell.ui.reader

/**
 * v0.72.0：音量键路由。
 *
 * Compose 拿不到音量键（系统在 Activity 层消费），由 MainActivity.dispatchKeyEvent
 * 转发：阅读器可见且开了"音量键翻页"时注册回调，返回 true 即消费掉按键。
 */
object VolumeKeyRouter {
    /** 返回 true 表示已消费，不再走系统音量。 */
    var onVolumeUp: (() -> Boolean)? = null
    var onVolumeDown: (() -> Boolean)? = null
}
