package com.movieshell.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v0.139.0：统一加载/状态环——一圈跑马灯 + 中央状态点，全系统共用。
 *
 * 外圈：加载中时一段 270° 光弧匀速旋转（跑马灯）；非加载时为同色细圆轨。
 * 中央点：绿 = 正常/运行中，黄 = 加载中/启动中，红 = 失败/异常，灰 = 未启动/已停止。
 * 颜色口径与服务页红绿灯（v0.138.0）一致，不另起一套。
 */
enum class StatusRingState {
    Ready,
    Loading,
    Failed,
    Idle,
}

fun StatusRingState.color(): Color = when (this) {
    StatusRingState.Ready -> Color(0xFF4CAF50)
    StatusRingState.Loading -> Color(0xFFFFC107)
    StatusRingState.Failed -> Color(0xFFE53935)
    StatusRingState.Idle -> Color(0xFF9E9E9E)
}

@Composable
fun StatusRing(
    state: StatusRingState,
    diameter: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    val c = state.color()
    val transition = rememberInfiniteTransition(label = "statusRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "statusRingAngle",
    )
    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val w = size.minDimension * 0.14f
            if (state == StatusRingState.Loading) {
                drawArc(
                    color = c.copy(alpha = 0.18f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = w),
                )
                drawArc(
                    color = c,
                    startAngle = angle,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = w, cap = StrokeCap.Round),
                )
            } else {
                drawArc(
                    color = c.copy(alpha = 0.30f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = w),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(diameter * 0.42f)
                .clip(CircleShape)
                .background(c),
        )
    }
}
