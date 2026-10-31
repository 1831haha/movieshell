package com.movieshell.ui.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.MusicPlayerLayout
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.theme.LocalBrandGradient
import com.movieshell.ui.theme.V5
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/** Icon for each playback layout option. */
fun MusicPlayerLayout.icon(): ImageVector = when (this) {
    MusicPlayerLayout.CLASSIC -> Icons.Filled.CropSquare
    MusicPlayerLayout.VINYL -> Icons.Filled.Album
    MusicPlayerLayout.IMMERSIVE -> Icons.Filled.Fullscreen
    MusicPlayerLayout.SPECTRUM -> Icons.Filled.GraphicEq
    MusicPlayerLayout.CARD -> Icons.Filled.AutoAwesome
}

/**
 * v0.294.12：播放界面选择面板（紧凑 TechDialog，点选即生效）。
 * v0.294.14：文案改为强调动态 / 炫彩动效。
 */
@Composable
fun MusicPlayerLayoutPickerDialog(
    current: MusicPlayerLayout,
    onDismiss: () -> Unit,
    onPick: (MusicPlayerLayout) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    TechDialog(
        onDismissRequest = onDismiss,
        title = "播放界面",
        dismissText = "完成",
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MusicPlayerLayout.entries.forEach { layout ->
                val selected = layout == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(V5.CornerMd))
                        .background(
                            if (selected) cs.secondaryContainer else Color.Transparent,
                        )
                        .clickable { onPick(layout) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) cs.primary.copy(alpha = 0.18f)
                                else cs.surfaceContainerHighest,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            layout.icon(),
                            contentDescription = null,
                            tint = if (selected) cs.primary else cs.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            layout.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) cs.onSecondaryContainer else cs.onSurface,
                        )
                        Text(
                            layout.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) {
                                cs.onSecondaryContainer.copy(alpha = 0.75f)
                            } else {
                                cs.onSurfaceVariant
                            },
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "当前",
                            tint = cs.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** v0.294.14：炫彩多停渐变色——主题 primary/secondary/tertiary + 品牌色带。 */
@Composable
fun rememberXuanCaiColors(): List<Color> {
    val cs = MaterialTheme.colorScheme
    val brand = LocalBrandGradient.current
    return remember(cs.primary, cs.secondary, cs.tertiary, brand) {
        buildList {
            add(cs.primary)
            add(cs.secondary)
            if (brand.isNotEmpty()) addAll(brand.take(3))
            add(cs.tertiary)
            add(cs.primary)
        }.distinct()
    }
}

/** v0.294.14：经典封面——播放时封面边缘炫彩柔光脉冲；暂停回落。 */
@Composable
fun ClassicCoverWithGlow(
    artUrl: String,
    playing: Boolean,
    reducedMotion: Boolean,
    coverScale: Float,
    coverAlpha: Float,
    side: Dp,
    corner: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    val colors = rememberXuanCaiColors()
    val pulse = remember { Animatable(0.35f) }
    LaunchedEffect(playing, reducedMotion) {
        if (!playing || reducedMotion) {
            pulse.animateTo(0.22f, tween(280))
        } else {
            while (true) {
                pulse.animateTo(0.95f, tween(1600, easing = FastOutSlowInEasing))
                pulse.animateTo(0.35f, tween(1600, easing = FastOutSlowInEasing))
            }
        }
    }
    val sweep = rememberInfiniteTransition(label = "classicSweep")
    val sweepAngle by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "classicSweepAngle",
    )
    Box(
        modifier = modifier.size(side),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val a = pulse.value
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors[0].copy(alpha = 0.55f * a),
                        colors.getOrElse(1) { colors[0] }.copy(alpha = 0.28f * a),
                        Color.Transparent,
                    ),
                ),
                radius = r * 1.08f,
            )
            if (playing && !reducedMotion) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = colors.map { it.copy(alpha = 0.55f * a) } +
                            colors.first().copy(alpha = 0.55f * a),
                    ),
                    startAngle = sweepAngle,
                    sweepAngle = 110f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                    size = size,
                )
            }
        }
        SquaredCoverArt(
            artUrl = artUrl,
            coverScale = coverScale,
            coverAlpha = coverAlpha,
            side = side * 0.92f,
            corner = corner,
        )
    }
}

/**
 * v0.294.12：律动频谱条。
 * v0.294.14：炫彩多色渐变条，更高更活。
 */
@Composable
fun SpectrumVisualizerBars(
    playing: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 28,
    maxHeight: Dp = 72.dp,
) {
    val bars = remember(barCount) { List(barCount) { Animatable(0.18f) } }
    val colors = rememberXuanCaiColors()
    LaunchedEffect(playing, reducedMotion, barCount) {
        if (!playing || reducedMotion) {
            bars.forEach { bar ->
                launch { bar.animateTo(0.18f, tween(220)) }
            }
        } else {
            bars.forEachIndexed { i, bar ->
                launch {
                    val up = 220 + (i % 7) * 45
                    val down = 200 + ((i + 3) % 5) * 50
                    val peak = 0.42f + ((i * 17) % 58) / 100f
                    while (true) {
                        bar.animateTo(peak.coerceIn(0.35f, 1f), tween(up, easing = LinearEasing))
                        bar.animateTo(0.12f + (i % 4) * 0.05f, tween(down, easing = LinearEasing))
                    }
                }
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEachIndexed { i, bar ->
            val c0 = colors[i % colors.size]
            val c1 = colors[(i + 2) % colors.size]
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(maxHeight)
                    .graphicsLayer {
                        scaleY = bar.value
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(c0.copy(alpha = 0.95f), c1.copy(alpha = 0.75f)),
                        ),
                    ),
            )
        }
    }
}

/**
 * v0.294.12：黑胶唱片封面。
 * v0.294.14：炫彩反光环 / 扫光随盘旋转。
 */
@Composable
fun VinylDiscCover(
    artUrl: String,
    playing: Boolean,
    reducedMotion: Boolean,
    coverAlpha: Float,
    side: Dp,
    modifier: Modifier = Modifier,
) {
    val angle = remember { Animatable(0f) }
    val colors = rememberXuanCaiColors()
    LaunchedEffect(playing, reducedMotion) {
        if (!playing || reducedMotion) return@LaunchedEffect
        while (true) {
            val next = angle.value + 360f
            angle.animateTo(
                next,
                animationSpec = tween(durationMillis = 9000, easing = LinearEasing),
            )
            if (angle.value >= 360f) {
                angle.snapTo(angle.value % 360f)
            }
        }
    }
    val light = rememberInfiniteTransition(label = "vinylLight")
    val lightAngle by light.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "vinylLightAngle",
    )
    val glowPulse = remember { Animatable(0.45f) }
    LaunchedEffect(playing, reducedMotion) {
        if (!playing || reducedMotion) {
            glowPulse.animateTo(0.25f, tween(240))
        } else {
            while (true) {
                glowPulse.animateTo(0.9f, tween(1400, easing = FastOutSlowInEasing))
                glowPulse.animateTo(0.4f, tween(1400, easing = FastOutSlowInEasing))
            }
        }
    }
    Box(
        modifier = modifier.size(side),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        colors[0].copy(alpha = 0.35f * glowPulse.value),
                        colors.getOrElse(1) { colors[0] }.copy(alpha = 0.15f * glowPulse.value),
                        Color.Transparent,
                    ),
                ),
                radius = r * 1.05f,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = if (reducedMotion) 0f else angle.value
                    alpha = coverAlpha
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0xFF2A2A2E),
                            Color(0xFF121216),
                            Color(0xFF0A0A0E),
                        ),
                    ),
                )
                .border(2.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = if (reducedMotion) 0f else angle.value
                    alpha = coverAlpha
                },
        ) {
            val stroke = 5.dp.toPx()
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = colors.map { it.copy(alpha = 0.7f * glowPulse.value) } +
                        colors.first().copy(alpha = 0.7f * glowPulse.value),
                ),
                radius = size.minDimension / 2f - stroke,
                style = Stroke(width = stroke),
            )
        }
        if (playing && !reducedMotion) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            colors[0].copy(alpha = 0.85f),
                            colors.getOrElse(2) { colors[0] }.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                    ),
                    startAngle = lightAngle,
                    sweepAngle = 80f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        val artSide = side * 0.72f
        if (artUrl.isNotBlank()) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(artSide)
                    .graphicsLayer {
                        rotationZ = if (reducedMotion) 0f else angle.value
                        alpha = coverAlpha
                    }
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(artSide)
                    .graphicsLayer {
                        rotationZ = if (reducedMotion) 0f else angle.value
                        alpha = coverAlpha
                    }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(artSide * 0.35f),
                    tint = Color.White.copy(alpha = 0.45f),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(side * 0.08f)
                .clip(CircleShape)
                .background(Color(0xFF07070D))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        )
    }
}

/** v0.294.14：炫彩——封面慢旋 + 轨道炫彩粒子 / 光环环绕。 */
@Composable
fun XuanCaiOrbitCover(
    artUrl: String,
    playing: Boolean,
    reducedMotion: Boolean,
    coverScale: Float,
    coverAlpha: Float,
    side: Dp,
    corner: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    val colors = rememberXuanCaiColors()
    val spin = remember { Animatable(0f) }
    LaunchedEffect(playing, reducedMotion) {
        if (!playing || reducedMotion) return@LaunchedEffect
        while (true) {
            val next = spin.value + 360f
            spin.animateTo(next, tween(14000, easing = LinearEasing))
            if (spin.value >= 360f) spin.snapTo(spin.value % 360f)
        }
    }
    val orbit = rememberInfiniteTransition(label = "orbit")
    val orbitAngle by orbit.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbitAngle",
    )
    val pulse = remember { Animatable(0.5f) }
    LaunchedEffect(playing, reducedMotion) {
        if (!playing || reducedMotion) {
            pulse.animateTo(0.3f, tween(240))
        } else {
            while (true) {
                pulse.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
                pulse.animateTo(0.45f, tween(1200, easing = FastOutSlowInEasing))
            }
        }
    }
    Box(
        modifier = modifier.size(side),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseR = size.minDimension * 0.48f
            for (ring in 0..2) {
                val rr = baseR * (0.78f + ring * 0.14f)
                drawCircle(
                    color = colors[ring % colors.size].copy(alpha = 0.22f * pulse.value),
                    radius = rr,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
            if (playing && !reducedMotion) {
                val n = 10
                for (i in 0 until n) {
                    val a = Math.toRadians((orbitAngle + i * (360f / n)).toDouble())
                    val ringR = baseR * (0.82f + (i % 3) * 0.12f)
                    val x = cx + (cos(a) * ringR).toFloat()
                    val y = cy + (sin(a) * ringR).toFloat()
                    val pr = (3.5f + (i % 3) * 1.8f).dp.toPx()
                    drawCircle(
                        color = colors[i % colors.size].copy(alpha = 0.85f * pulse.value),
                        radius = pr,
                        center = Offset(x, y),
                    )
                    drawCircle(
                        color = colors[i % colors.size].copy(alpha = 0.25f * pulse.value),
                        radius = pr * 2.2f,
                        center = Offset(x, y),
                    )
                }
                drawArc(
                    brush = Brush.sweepGradient(
                        colors.map { it.copy(alpha = 0.55f * pulse.value) } +
                            colors.first().copy(alpha = 0.55f * pulse.value),
                    ),
                    startAngle = orbitAngle * 1.4f,
                    sweepAngle = 100f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            } else {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors.map { it.copy(alpha = 0.25f) } + colors.first().copy(alpha = 0.25f),
                    ),
                    radius = baseR * 0.9f,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        SquaredCoverArt(
            artUrl = artUrl,
            coverScale = coverScale,
            coverAlpha = coverAlpha,
            side = side * 0.62f,
            corner = corner,
            modifier = Modifier.graphicsLayer {
                rotationZ = if (reducedMotion) 0f else spin.value
            },
        )
    }
}

/**
 * v0.294.14：沉浸氛围——流动炫彩极光 / 渐变光斑（非静态模糊）。
 */
@Composable
fun ImmersiveAuroraOverlay(
    playing: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = rememberXuanCaiColors()
    if (reducedMotion) return
    val t = rememberInfiniteTransition(label = "aurora")
    val p1 by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(11000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aurora1",
    )
    val p2 by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aurora2",
    )
    val p3 by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aurora3",
    )
    val strength = if (playing) 1f else 0.35f
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors[0].copy(alpha = 0.38f * strength),
                    Color.Transparent,
                ),
                center = Offset(w * (0.2f + 0.35f * p1), h * (0.18f + 0.25f * p2)),
                radius = w * 0.55f,
            ),
            radius = w * 0.55f,
            center = Offset(w * (0.2f + 0.35f * p1), h * (0.18f + 0.25f * p2)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.getOrElse(1) { colors[0] }.copy(alpha = 0.32f * strength),
                    Color.Transparent,
                ),
                center = Offset(w * (0.75f - 0.3f * p2), h * (0.55f + 0.2f * p1)),
                radius = w * 0.5f,
            ),
            radius = w * 0.5f,
            center = Offset(w * (0.75f - 0.3f * p2), h * (0.55f + 0.2f * p1)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.getOrElse(2) { colors[0] }.copy(alpha = 0.28f * strength),
                    Color.Transparent,
                ),
                center = Offset(w * (0.45f + 0.25f * p3), h * (0.78f - 0.3f * p2)),
                radius = w * 0.48f,
            ),
            radius = w * 0.48f,
            center = Offset(w * (0.45f + 0.25f * p3), h * (0.78f - 0.3f * p2)),
        )
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    colors[0].copy(alpha = 0.08f * strength),
                    colors.getOrElse(2) { colors[0] }.copy(alpha = 0.06f * strength),
                    Color.Transparent,
                ),
                start = Offset(0f, h * p1),
                end = Offset(w, h * (1f - p2)),
            ),
        )
    }
}

/** v0.294.12：圆角方封面。 */
@Composable
fun SquaredCoverArt(
    artUrl: String,
    coverScale: Float,
    coverAlpha: Float,
    side: Dp,
    corner: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    if (artUrl.isNotBlank()) {
        AsyncImage(
            model = artUrl,
            contentDescription = null,
            modifier = modifier
                .size(side)
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                    alpha = coverAlpha
                }
                .clip(RoundedCornerShape(corner)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .size(side)
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                    alpha = coverAlpha
                }
                .clip(RoundedCornerShape(corner))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(corner)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(side * 0.28f),
                tint = Color.White.copy(alpha = 0.45f),
            )
        }
    }
}
