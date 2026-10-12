package com.movieshell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val palettes = listOf(
    Color(0xFF243B6B) to Color(0xFF7C9CFF),
    Color(0xFF5B2A4A) to Color(0xFFE08BB8),
    Color(0xFF1F4D3A) to Color(0xFF6FD3A7),
    Color(0xFF6B3A1F) to Color(0xFFE8A75C),
    Color(0xFF3A2A6B) to Color(0xFFA78BFA),
    Color(0xFF0F4C5C) to Color(0xFF5CC8E8),
    Color(0xFF5C1F1F) to Color(0xFFE87A5C),
    Color(0xFF2A4A1F) to Color(0xFF9DE06B),
)

private fun paletteFor(key: String): Pair<Color, Color> {
    val idx = (key.hashCode() and Int.MAX_VALUE) % palettes.size
    return palettes[idx]
}

/**
 * 海报卡片（v0.12.1：有真实海报 URL 时用 Coil 加载，无 URL 时回退渐变占位）。
 */
@Composable
fun PosterCard(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    posterUrl: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val (c1, c2) = remember(title) { paletteFor(title) }
    Card(
        modifier = modifier
            // v0.18.0：影图式毛玻璃卡片——半透明底 + 微光描边
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.08f),
        ),
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(c1, c2))),
        ) {
            // 真实海报（Coil 异步加载，失败/无 URL 时显示渐变占位）
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            // 底部压暗，衬标题
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color(0xAA000000),
                        )
                    )
            )
            if (badge != null) {
                Text(
                    text = badge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            // 无海报时显示装饰大字（取标题首字做水印感）
            if (posterUrl.isNullOrBlank()) {
                Text(
                    text = title.firstOrNull()?.toString() ?: "",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 24.dp),
                    color = Color.White.copy(alpha = 0.25f),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 纯渐变块（用于详情页头图、搜索结果缩略图等）。
 */
@Composable
fun GradientBlock(
    key: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val (c1, c2) = remember(key) { paletteFor(key) }
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
