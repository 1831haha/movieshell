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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.movieshell.ui.theme.LocalBrandGradient

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
    // v0.279.0 V4：胶囊渐变 tab 改 V4 分段控件（s1 轨道 + s3 选中块，无渐变），
    // 页边距 12dp；参数与行为不变。
    V5Segmented(
        options = titles,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        height = 32.dp,
    )
}
