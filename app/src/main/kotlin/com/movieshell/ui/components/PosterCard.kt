package com.movieshell.ui.components

import com.movieshell.ui.theme.V5

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 海报卡片（v0.32.0 影院黑毛玻璃 → v0.279.0 V4：无卡片底无描边，
 * 只有 6dp 圆角海报 + 1px 12% 圆环占位；左上角等宽小标签（含 VIP 用琥珀色，
 * 其余备注用暗底标签）；下面 11.5/500 片名一行 + t3 10.5 副标题）。
 * 无海报/图挂时用 V4 柔和渐变占位 + 片名首字，不用假海报。
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
    val v = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(V5.CornerMd)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(V5.CornerMd))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        V5Art(
            seed = title,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = shape,
            ring = posterUrl.isNullOrBlank(),
        ) {
            // v0.212.0 审计修复：海报加载失败原来是空白块，地址有效但图
            // 挂了时不回落占位；失败后改显示标题首字占位，与无图同款。
            var imageFailed by remember(posterUrl) { mutableStateOf(false) }
            if (!posterUrl.isNullOrBlank() && !imageFailed) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                )
            } else {
                Text(
                    text = title.firstOrNull()?.toString() ?: "",
                    modifier = Modifier.align(Alignment.Center),
                    color = v.onSurface.copy(alpha = 0.22f),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // 1px 内描边，海报与平底分开
            Box(
                Modifier
                    .matchParentSize()
                    .border(1.dp, v.outlineVariant, shape),
            )
            if (!badge.isNullOrBlank()) {
                val isVip = badge.contains("vip", ignoreCase = true)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(V5.CornerXs))
                        .background(if (isVip) Color(0xE6151208) else Color(0xB3000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isVip) v.tertiary else Color(0xFFE6E8EE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(top = 6.dp, start = 1.dp, end = 1.dp, bottom = 2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = v.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = v.outline,
                )
            }
        }
    }
}

/** 纯色块（用于详情页头图、搜索结果缩略图等）。 */
@Composable
fun PlainBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) { content() }
}
