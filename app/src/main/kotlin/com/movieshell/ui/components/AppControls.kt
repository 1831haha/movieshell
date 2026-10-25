package com.movieshell.ui.components

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
import com.movieshell.ui.theme.SoftDimens

/**
 * v0.211.0：全仓统一精致控件（替代系统原生默认样式）。
 *
 * 之前各屏直接用 Material3 默认 Switch/Slider/DropdownMenu：
 * 开关是系统紫、滑杆是默认细线、菜单是白底方角——跟影图风
 * （深底 + 毛玻璃 + 微光描边 + 青色强调）完全不搭。
 * 新口径：各屏不许再直接调这三个原生控件，一律走这里，
 * 颜色、圆角、描边一次定死，后面换主题色只改这一处。
 */

/** 开关：开 = 青色 #4DD0E1（与底部 dock 选中色同源），关 = 灰蓝，拇指恒白。 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = Color(0xFF4DD0E1)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = accent,
            checkedBorderColor = accent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF3A4356),
            uncheckedBorderColor = Color(0xFF3A4356),
        ),
    )
}

/** 滑杆：青色轨道 + 白色大拇指（阅读器进度、音量类场景）。 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val accent = Color(0xFF4DD0E1)
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = accent,
            inactiveTrackColor = Color(0xFF3A4356),
        ),
    )
}

/** 下拉菜单：毛玻璃底 + 20dp 圆角 + 微光描边，跟 TechDialog 同源。 */
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
        shape = RoundedCornerShape(SoftDimens.CardCorner),
        containerColor = MaterialTheme.colorScheme.surface,
        content = content,
    )
}
