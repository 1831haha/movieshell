package com.movieshell.ui.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ViewCompact
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.MusicPlayerLayout
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.theme.V5
import kotlinx.coroutines.launch

/** Icon for each playback layout option. */
fun MusicPlayerLayout.icon(): ImageVector = when (this) {
    MusicPlayerLayout.CLASSIC -> Icons.Filled.CropSquare
    MusicPlayerLayout.VINYL -> Icons.Filled.Album
    MusicPlayerLayout.IMMERSIVE -> Icons.Filled.Fullscreen
    MusicPlayerLayout.SPECTRUM -> Icons.Filled.GraphicEq
    MusicPlayerLayout.CARD -> Icons.Filled.ViewCompact
}

/**
 * v0.294.12：播放界面选择面板（紧凑 TechDialog，点选即生效）。
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

/**
 * v0.294.12：律动频谱条——播放时错峰起伏，暂停回落；减动效时恒静态矮条。
 * 假平滑动画（不依赖 FFT），视觉跟随播放状态。
 */
@Composable
fun SpectrumVisualizerBars(
    playing: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    maxHeight: Dp = 56.dp,
) {
    val bars = remember(barCount) { List(barCount) { Animatable(0.18f) } }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    LaunchedEffect(playing, reducedMotion, barCount) {
        if (!playing || reducedMotion) {
            bars.forEach { bar ->
                launch { bar.animateTo(0.18f, tween(220)) }
            }
        } else {
            bars.forEachIndexed { i, bar ->
                launch {
                    // Staggered phase so the strip looks like a spectrum, not a wave.
                    val up = 280 + (i % 7) * 55
                    val down = 260 + ((i + 3) % 5) * 60
                    val peak = 0.35f + ((i * 17) % 65) / 100f
                    while (true) {
                        bar.animateTo(peak.coerceIn(0.3f, 1f), tween(up, easing = LinearEasing))
                        bar.animateTo(0.15f + (i % 4) * 0.04f, tween(down, easing = LinearEasing))
                    }
                }
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEachIndexed { i, bar ->
            val color = if (i % 3 == 0) secondary else primary
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
                    .background(color.copy(alpha = 0.85f)),
            )
        }
    }
}

/**
 * v0.294.12：黑胶唱片封面——圆形封面 + 外环 + 中心孔；播放时匀速旋转（约 9 秒一圈），
 * 暂停停在当前角度；减动效时不转。
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
    LaunchedEffect(playing, reducedMotion) {
        if (!playing || reducedMotion) return@LaunchedEffect
        // Continue from current angle; 9s per full turn (same as v0.130.0).
        while (true) {
            val next = angle.value + 360f
            angle.animateTo(
                next,
                animationSpec = tween(durationMillis = 9000, easing = LinearEasing),
            )
            // Keep angle bounded so float doesn't grow forever.
            if (angle.value >= 360f) {
                angle.snapTo(angle.value % 360f)
            }
        }
    }
    Box(
        modifier = modifier.size(side),
        contentAlignment = Alignment.Center,
    ) {
        // Outer vinyl ring
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
        // Album art disc (slightly inset)
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
        // Center spindle hole
        Box(
            modifier = Modifier
                .size(side * 0.08f)
                .clip(CircleShape)
                .background(Color(0xFF07070D))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        )
    }
}

/**
 * v0.294.12：圆角方封面（经典 / 沉浸 / 频谱 / 卡片共用），卡片布局用更小圆角。
 */
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
