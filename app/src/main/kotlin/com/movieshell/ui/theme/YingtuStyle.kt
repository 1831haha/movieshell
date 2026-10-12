package com.movieshell.ui.theme

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * v0.18.0：影图式流动光斑背景。
 *
 * 深色底 + 两个缓慢漂移的彩色光斑（主题色 + 辅助色），营造"流动、虚幻"感。
 * 光斑用 Canvas 画圆 + blur 实现柔光，位置随时间正弦漂移。
 */
@Composable
fun FlowingGlowBackground(
    themeColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "glow")
    // 光斑1：水平漂移
    val x1 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "x1",
    )
    // 光斑1：垂直漂移
    val y1 by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "y1",
    )
    // 光斑2：反向漂移
    val x2 by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.25f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "x2",
    )
    val y2 by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.4f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(11000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "y2",
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 深色底
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFF0B0E14))
        }
        // 光斑层（blur 柔光）
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(80.dp)
        ) {
            val w = size.width
            val h = size.height
            // 光斑1：主题色
            drawCircle(
                color = themeColor.copy(alpha = 0.35f),
                radius = w * 0.45f,
                center = Offset(w * x1, h * y1),
            )
            // 光斑2：辅助色（偏紫）
            drawCircle(
                color = Color(0xFF7B61FF).copy(alpha = 0.25f),
                radius = w * 0.4f,
                center = Offset(w * x2, h * y2),
            )
            // 光斑3：底部微光
            drawCircle(
                color = themeColor.copy(alpha = 0.15f),
                radius = w * 0.5f,
                center = Offset(w * 0.5f, h * 1.1f),
            )
        }
        // 内容层
        content()
    }
}

/**
 * 影图式毛玻璃卡片修饰。
 * 半透明底 + 微光描边，配合 FlowingGlowBackground 使用。
 */
fun frostedGlassBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.12f),
        Color.White.copy(alpha = 0.04f),
    )
)

/** 毛玻璃卡片描边色 */
fun frostedGlassBorder(): Color = Color.White.copy(alpha = 0.15f)
