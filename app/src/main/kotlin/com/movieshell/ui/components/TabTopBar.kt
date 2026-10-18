package com.movieshell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.ui.theme.SoftType
import com.movieshell.ui.theme.LocalBrandGradient

/**
 * v0.80.0：各 tab / 二级页统一窄顶栏（向首页、详情页学习）。
 *
 * - 48dp 高（Material3 TopAppBar 默认 64dp 太粗），透明背景，跟页面流动背景融为一体，
 *   顶栏与内容之间不断层、过渡自然；
 * - 底部 1.5dp 四色渐变细线（呼应详情页 v0.78.2 的 2.5dp 线，更细更柔），既是分隔又是品牌点缀；
 * - 标题香槟金小字（沿用 v0.39.0 mockup 风格），右侧放操作图标。
 */
val BrandGradientColors = listOf(
    Color(0xFF22D3EE), // 青
    Color(0xFF818CF8), // 靛
    Color(0xFFE879F9), // 品红
    Color(0xFFFBBF24), // 金
)

@Composable
fun TabTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                // 无返回键时左侧留 12dp，与标题对齐
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            }
            Text(
                text = title,
                style = SoftType.Title,
                color = Color(0xFFD9C39A),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        // 底部炫彩细线：分隔顶栏与内容，但不断层（渐变、细）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(Brush.horizontalGradient(LocalBrandGradient.current)),
        )
    }
}
