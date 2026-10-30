package com.movieshell.ui.components

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.Spacer

import com.movieshell.ui.theme.V5
import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import com.movieshell.ui.theme.LocalBrandGradient

/**
 * v0.80.0：各 tab / 二级页统一窄顶栏（向首页、详情页学习）。
 *
 * - 48dp 高（Material3 TopAppBar 默认 64dp 太粗），透明背景，跟页面流动背景融为一体，
 *   顶栏与内容之间不断层、过渡自然；
 * - 底部 1.5dp 四色渐变细线（呼应详情页 v0.78.2 的 2.5dp 线，更细更柔），既是分隔又是品牌点缀；
 * - v0.279.0：42dp + 1px 分隔线；v0.292.0 V5：M3 small top app bar 64dp。
 * - 标题香槟金小字（沿用 v0.39.0 mockup 风格），右侧放操作图标。
 */

@Composable
fun TabTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // v0.292.0 V5：M3 small top app bar——64dp、titleLarge 22/28 500 −0.2 字距、onSurface；
    // 无返回键时标题起点 16dp，有返回键时 48dp 图标按钮在 4dp 内缩处；操作区 48dp 图标按钮 + 4dp 尾距；无分隔线。
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(V5.TopBar)
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = cs.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CompositionLocalProvider(LocalContentColor provides cs.onSurfaceVariant) {
            actions()
        }
    }
}
