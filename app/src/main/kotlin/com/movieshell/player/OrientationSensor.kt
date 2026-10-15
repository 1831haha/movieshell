package com.movieshell.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * v0.27.7：重力感应方向监听（单例，全 App 唯一的 sensor 监听点）。
 *
 * 根据加速度计判断横/竖屏：|x| 明显大于 |y| 视为横屏，反之竖屏，
 * 阈值防抖，只在状态翻转时发射，避免平放抖动。
 */
object OrientationSensor {

    /** true=横屏，false=竖屏 */
    private val _isLandscape = MutableStateFlow(false)
    val isLandscape: StateFlow<Boolean> = _isLandscape

    private var started = false
    private var sensorManager: SensorManager? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val land = when {
                abs(x) - abs(y) > 2.5f -> true
                abs(y) - abs(x) > 2.5f -> false
                else -> return
            }
            if (land != _isLandscape.value) _isLandscape.value = land
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
        _isLandscape.value = false
    }
}
