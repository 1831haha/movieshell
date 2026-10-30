package com.movieshell.ui.components

import com.movieshell.ui.theme.V5

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * v0.211.0：全仓统一精致控件（替代系统原生默认样式）。
 *
 * 之前各屏直接用 Material3 默认 Switch/Slider/DropdownMenu：
 * 开关是系统紫、滑杆是默认细线、菜单是白底方角——跟影图风
 * （深底 + 毛玻璃 + 微光描边 + 青色强调）完全不搭。
 * 新口径：各屏不许再直接调这三个原生控件，一律走这里，
 * 颜色、圆角、描边一次定死，后面换主题色只改这一处。
 */

/**
 * 开关（v0.279.0 V4：32×18 小开关——开 = 强调色底 + 白拇指，关 = s3 底 + 灰拇指）。
 * 自绘而不用 Material Switch（52×32 太大，和 44dp 设置行比例不对）；触摸区仍 ≥ 40dp。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    V5Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
}

/** 滑杆：强调色轨道 + 白色拇指（阅读器进度、音量类场景）。 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val v = MaterialTheme.colorScheme
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = v.primary,
            inactiveTrackColor = v.surfaceContainerHighest,
        ),
    )
}

/** 下拉菜单（V4：s1 底 + 8dp 圆角 + 1px 分隔线描边）。 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(V5.CornerMd),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}
