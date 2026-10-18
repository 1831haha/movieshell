package com.movieshell.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.movieshell.ui.theme.LocalBrandGradient
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftType

/**
 * v0.91.0：胶囊分段 tab（原 v0.90.0 SourcesScreen 私有实现，抽成公共组件）。
 *
 * 影图系风格：整条圆角胶囊容器 + 毛玻璃深底 + 描边，等分胶囊；
 * 选中项品牌渐变（LocalBrandGradient，跟随主题）填充 + 白色加粗字，
 * 背景/字色带过渡动画。替代各屏样式不一的 TabRow。
 */
@Composable
fun CapsuleTabBar(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.horizontalGradient(LocalBrandGradient.current)
    val soft = LocalSoftExtra.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, soft.border, RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        titles.forEachIndexed { i, title ->
            val isSel = i == selected
            val bgAlpha by animateFloatAsState(if (isSel) 1f else 0f, label = "capsuleBg")
            val textColor by animateColorAsState(
                if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "capsuleText",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(gradient, alpha = bgAlpha)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = SoftType.Body,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    color = textColor,
                    maxLines = 1,
                )
            }
        }
    }
}
