package com.movieshell.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 定时关闭单入口（v0.21.0）。
 *
 * - [startMinutes]：N 分钟后到期，到期回调由调用方暂停播放；
 * - [armAfterEpisode]：布防「播完本集停止」，播放器在集尾用 [consumeAfterEpisode] 消费；
 * - [remainingMs]：剩余毫秒（0=未启用），UI 可展示倒计时；
 * - [cancel]：取消全部。
 *
 * 用 Handler + 单调时钟，App 退到后台也照常走。
 */
object SleepTimer {

    private val handler = Handler(Looper.getMainLooper())
    private var expireAt = 0L
    private var onExpire: (() -> Unit)? = null

    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs: StateFlow<Long> = _remainingMs

    private val _afterEpisodeArmed = MutableStateFlow(false)
    val afterEpisodeArmed: StateFlow<Boolean> = _afterEpisodeArmed

    private val tick = object : Runnable {
        override fun run() {
            val left = expireAt - SystemClock.uptimeMillis()
            if (left <= 0) {
                _remainingMs.value = 0L
                onExpire?.invoke()
                onExpire = null
            } else {
                _remainingMs.value = left
                handler.postDelayed(this, 1000)
            }
        }
    }

    /** N 分钟后到期。 */
    fun startMinutes(minutes: Int, onExpire: () -> Unit) {
        cancel()
        expireAt = SystemClock.uptimeMillis() + minutes * 60_000L
        this.onExpire = onExpire
        handler.post(tick)
    }

    /** 布防「播完本集停止」。 */
    fun armAfterEpisode() {
        cancel()
        _afterEpisodeArmed.value = true
    }

    /**
     * 集尾调用：已布防则消费并返回 true（调用方暂停而非切下一集），
     * 未布防返回 false。
     */
    fun consumeAfterEpisode(): Boolean {
        if (!_afterEpisodeArmed.value) return false
        _afterEpisodeArmed.value = false
        return true
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        expireAt = 0L
        onExpire = null
        _afterEpisodeArmed.value = false
        _remainingMs.value = 0L
    }

    /** 是否有定时在跑（倒计时或播完本集任一）。 */
    fun isActive(): Boolean = _remainingMs.value > 0 || _afterEpisodeArmed.value
}
