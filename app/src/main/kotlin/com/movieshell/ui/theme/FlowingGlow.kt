package com.movieshell.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * v0.32.0 影院黑：首页顶部流动渐变光斑。
 *
 * 三团径向渐变（暗金/靛紫/深蓝）以 18-30 秒长周期缓慢漂移，透明度压到 0.12 以下，
 * 不晃眼。省电：长周期意味着每帧位移极小；App 进后台时 Compose 停止重组，动画自然暂停。
 */
@Composable
fun FlowingGlowBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "glow")
    val driftA by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftA",
    )
    val driftB by transition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(24000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftB",
    )
    val driftC by transition.animateFloat(
        initialValue = 0.3f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(30000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftC",
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 只在顶部 55% 区域画光斑，下方保持纯黑
        val glowH = h * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFFD4A24C).copy(alpha = 0.12f),
                1f to Color.Transparent,
                center = Offset(w * (0.15f + 0.5f * driftA), glowH * 0.35f),
                radius = w * 0.55f,
            ),
            radius = w * 0.55f,
            center = Offset(w * (0.15f + 0.5f * driftA), glowH * 0.35f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF6D5BD0).copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = Offset(w * (0.85f - 0.5f * driftB), glowH * 0.55f),
                radius = w * 0.5f,
            ),
            radius = w * 0.5f,
            center = Offset(w * (0.85f - 0.5f * driftB), glowH * 0.55f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF2E5AAC).copy(alpha = 0.09f),
                1f to Color.Transparent,
                center = Offset(w * (0.3f + 0.4f * driftC), glowH * 0.75f),
                radius = w * 0.45f,
            ),
            radius = w * 0.45f,
            center = Offset(w * (0.3f + 0.4f * driftC), glowH * 0.75f),
        )
    }
}
