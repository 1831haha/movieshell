package com.movieshell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v0.294.6：全 App 统一搜索框（用户要求“优化各个界面的搜索框”）。
 *
 * Material You 胶囊：整圆角、surfaceContainerHigh 填充、无下划线无描边；前置放大镜，
 * 有字时尾部出清空 ×；单行；键盘回车键为「搜索」——触发 [onSearch] 并收起键盘；
 * 默认 48dp 高，紧凑。[trailing] 是可选尾部槽（筛选、来源等小图标按钮）。
 * 只做外观与键盘行为，防抖/历史/联想仍由各页面自己的 onValueChange 与状态负责。
 */
@Composable
fun AppSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** 键盘「搜索」与点放大镜时调用；为 null 时只收起键盘（实时筛选类列表）。 */
    onSearch: ((String) -> Unit)? = null,
    /** 点清空 ×；默认清成空串。 */
    onClear: () -> Unit = { onValueChange("") },
    height: Dp = 48.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val requester = focusRequester ?: remember { FocusRequester() }
    fun submit() {
        onSearch?.invoke(value)
        keyboard?.hide()
        focusManager.clearFocus()
    }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    }
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .then(if (onSearch != null && enabled) Modifier.clickable { submit() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = if (onSearch != null) "搜索" else null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                .focusRequester(requester)
                .then(
                    if (onFocusChanged != null) Modifier.onFocusChanged { onFocusChanged(it.isFocused) }
                    else Modifier,
                ),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty() && enabled) {
            IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "清空",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else if (trailing == null) {
            Spacer(Modifier.width(8.dp))
        }
        trailing?.invoke()
    }
}

/** v0.294.6：搜索历史词——紧凑 InputChip（点词即搜，尾部 × 删除单条）。 */
@Composable
fun AppHistoryChip(
    text: String,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    InputChip(
        selected = false,
        onClick = onClick,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(50),
        colors = InputChipDefaults.inputChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = null,
        trailingIcon = onRemove?.let { rm ->
            {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "删除",
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .clickable(onClick = rm),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
