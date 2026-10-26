package com.movieshell.ui.components

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
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType

/**
 * 海报卡片（v0.32.0 影院黑：毛玻璃质感——半透明底 + 低透明白描边；
 * 无海报时用中性占位 + 标题首字，不再用高饱和渐变）。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun PosterCard(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    posterUrl: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // v0.237.0 浅色高密度：浅色下按参考图——卡片本身无底无边，
    // 只有海报图带小圆角；角标放右上角黑底白字胶囊；标题 plain 黑字
    // 一行省略（不跑马灯）；深色保持原来的毛玻璃卡片。
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val shape = RoundedCornerShape(if (isLight) 8.dp else SoftDimens.PosterCorner)
    Column(
        modifier = modifier
            .then(
                if (isLight) Modifier
                else Modifier
                    .clip(shape)
                    .background(LocalSoftExtra.current.glass)
                    .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, shape)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
                // 底部压暗衬标题（浅色下角标已挪到右上角，不用压暗）
                if (!isLight) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.6f to Color.Transparent,
                                    1f to Color(0x88000000),
                                )
                            )
                    )
                }
            } else {
                Text(
                    text = title.firstOrNull()?.toString() ?: "",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // v0.80.0：备注改海报底部一行（不再大色块遮海报）
            // v0.212.0 审计修复：备注原来是四色渐变字 + 跑马灯，与香槟金/
            // 青色体系冲突、一屏十几条同时跑很闹；改单色青色 + 省略号。
            // v0.237.0：浅色下角标按参考图放右上角黑底白字胶囊。
            if (badge != null) {
                if (isLight) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badge,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                    }
                } else {
                    Text(
                        text = badge,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        style = TextStyle(
                            color = Color(0xFF4DD0E1),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(
            modifier = if (isLight) Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
            else Modifier.padding(8.dp)
        ) {
            if (isLight) {
                Text(
                    text = title,
                    style = SoftType.CardTitle.copy(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                // v0.90.0：片名底色改护眼绿胶囊 + 深色字保证对比度
                // v0.212.0 审计修复：那块写死的浅绿底 + 深绿字与整页深底 +
                // 毛玻璃 + 微光描边断裂，深色主题下最扎眼；改跟卡片同款
                // 毛玻璃底 + 微光描边，字色跟主题 onSurface 走。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(LocalSoftExtra.current.glass)
                        .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        // v0.82.0：片名字体调小 + 显示不全跑马灯
                        style = SoftType.CardTitle.copy(fontSize = 13.sp),
                        modifier = Modifier.basicMarquee(),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SoftType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
