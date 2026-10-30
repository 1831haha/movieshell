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
 * 开关（v0.292.0 V5：M3 Switch 52×32，开 = primary 轨道；设置行改 56/72dp 后比例合适）。
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

/** 滑杆（v0.292.0 V5：M3 primary 轨道 + primary 拇指，未走轨道 secondaryContainer）。 */
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
            thumbColor = v.primary,
            activeTrackColor = v.primary,
            inactiveTrackColor = v.secondaryContainer,
        ),
    )
}

/** 下拉菜单（v0.292.0 V5：M3 Menu——surfaceContainer 底、16dp 圆角、3dp 阴影、无描边）。 */
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
        shape = RoundedCornerShape(V5.CornerLg),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}
