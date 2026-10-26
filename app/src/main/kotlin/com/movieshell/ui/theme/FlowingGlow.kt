package com.movieshell.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

/**
 * v0.33.0 影院黑：首页顶部流动渐变光斑（风格统一后：青 #4DD0E1 / 蓝 #2086FF / 青绿 #2DD4BF）。
 *
 * 三团径向渐变以 18-30 秒长周期缓慢漂移，透明度压到 0.12 以下，
 * 不晃眼。省电：长周期意味着每帧位移极小；App 进后台时 Compose 停止重组，动画自然暂停。
 */
@Composable
fun FlowingGlowBackground(modifier: Modifier = Modifier) {
    val driftA = remember { Animatable(0f) }
    val driftB = remember { Animatable(1f) }
    val driftC = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) {
        launch {
            driftA.animateTo(
                1f,
                infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse),
            )
        }
        launch {
            driftB.animateTo(
                0f,
                infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Reverse),
            )
        }
        launch {
            driftC.animateTo(
                0.9f,
                infiniteRepeatable(tween(30000, easing = LinearEasing), RepeatMode.Reverse),
            )
        }
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 只在顶部 55% 区域画光斑，下方保持纯黑
        val glowH = h * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF4DD0E1).copy(alpha = 0.12f),
                1f to Color.Transparent,
                center = Offset(w * (0.15f + 0.5f * driftA.value), glowH * 0.35f),
                radius = w * 0.55f,
            ),
            radius = w * 0.55f,
            center = Offset(w * (0.15f + 0.5f * driftA.value), glowH * 0.35f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF2086FF).copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = Offset(w * (0.85f - 0.5f * driftB.value), glowH * 0.55f),
                radius = w * 0.5f,
            ),
            radius = w * 0.5f,
            center = Offset(w * (0.85f - 0.5f * driftB.value), glowH * 0.55f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF2DD4BF).copy(alpha = 0.09f),
                1f to Color.Transparent,
                center = Offset(w * (0.3f + 0.4f * driftC.value), glowH * 0.75f),
                radius = w * 0.45f,
            ),
            radius = w * 0.45f,
            center = Offset(w * (0.3f + 0.4f * driftC.value), glowH * 0.75f),
        )
    }
}
