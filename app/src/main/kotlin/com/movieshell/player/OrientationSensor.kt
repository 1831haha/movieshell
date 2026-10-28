package com.movieshell.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * v0.27.7：重力感应方向监听（单例，全 App 唯一的 sensor 监听点）。
 *
 * 根据加速度计判断横/竖屏：|x| 明显大于 |y| 视为横屏，反之竖屏，
 * 阈值防抖，只在状态翻转时发射，避免平放抖动。
 * v0.256.0：加时间防抖 + 迟滞——候选方向必须连续稳定 400ms 才下发，
 * 且进出方向的差值阈值从 2.5 提高到 3.0；之前 2 秒内横竖屏翻 4-5 次，
 * 横竖屏反复重建正是“横屏回来左边缺一块”的首要嫌疑。
 */
object OrientationSensor {

    /** true=横屏，false=竖屏 */
    private val _isLandscape = MutableStateFlow(false)
    val isLandscape: StateFlow<Boolean> = _isLandscape

    /** v0.261.0：手动强制切换锁——手动点横竖屏按钮后，重力感应不再把方向抢回去，退出播放页时清锁。 */
    @Volatile
    var manualLocked = false

    private var started = false
    private var sensorManager: SensorManager? = null

    /** 候选方向与首次出现时间；候选翻回或回到死区时清零重计。 */
    private var candidate: Boolean? = null
    private var candidateSince = 0L
    private val stableMs = 400L
    private val diffThreshold = 3.0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val cand = when {
                abs(x) - abs(y) > diffThreshold -> true
                abs(y) - abs(x) > diffThreshold -> false
                else -> return
            }
            if (cand == _isLandscape.value) {
                candidate = null
                return
            }
            val now = SystemClock.uptimeMillis()
            if (cand != candidate) {
                candidate = cand
                candidateSince = now
                return
            }
            if (now - candidateSince >= stableMs) {
                _isLandscape.value = cand
                candidate = null
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 幂等启动；重复调用不重复注册。 */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val sm = context.applicationContext
            .getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    /** 幂等停止；重置方向状态，避免下次进入时沿用旧值。 */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(listener)
        sensorManager = null
        candidate = null
        manualLocked = false
        _isLandscape.value = false
    }
}
