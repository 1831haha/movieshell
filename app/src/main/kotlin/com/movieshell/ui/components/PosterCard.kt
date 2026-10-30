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

/** 海报卡片（v0.292.0 V5 M3 口径，见函数内注释）。 */
@Composable
fun PosterCard(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    posterUrl: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // v0.292.0 V5：海报 12dp 圆角 2:3（比例由调用方定），左下角标（62% 黑底 + labelSmall 白字；VIP 用 tertiaryContainer），
    // 下面 titleSmall 14/500 片名 + bodySmall onSurfaceVariant 副标题。无图/图挂时主题色柔和占位 + 片名首字。
    val cs = MaterialTheme.colorScheme
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
            // v0.212.0 审计修复：海报加载失败后改显示标题首字占位，与无图同款。
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
                    color = cs.onSurface.copy(alpha = 0.28f),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!badge.isNullOrBlank()) {
                val isVip = badge.contains("vip", ignoreCase = true)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(V5.CornerXs))
                        .background(if (isVip) cs.tertiaryContainer else Color(0x9E000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isVip) cs.onTertiaryContainer else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp, bottom = 2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = cs.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = cs.onSurfaceVariant,
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
