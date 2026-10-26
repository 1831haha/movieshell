package com.movieshell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movieshell.ui.theme.LocalBrandGradient

/**
 * v0.84.0：科技感弹窗——深色玻璃拟态底 + 四色渐变描边 + 标题下渐变细线。
 * 替代默认 AlertDialog（在深色主题下发白、输入框看不清，用户反馈"导入框框都看不到"）。
 * v0.194.0：弹窗体跟随系统主题——之前恒深色 #171B28，用户在浅色主题下嫌
 * “所有弹窗页都太暗了”；现深色主题仍走深底、浅色主题走浅底，内容色一律
 * 跟主题 onSurface/onSurfaceVariant 走，此前各弹窗里写死的浅色一并改掉
 * （见 BookDetailDialog/ReaderScreen/MusicPlayerScreen 等），不许再写死。
 */
@Composable
fun TechDialog(
    onDismissRequest: () -> Unit,
    title: String,
    subtitle: String? = null,
    confirmText: String = "确定",
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    /** 取消按钮文案；传 null 则隐藏取消按钮（如不可取消的进度弹窗） */
    dismissText: String? = "取消",
    /** v0.240.0：弹窗宽度占屏幕宽的比例；null 则走平台默认宽（其他弹窗不受影响）。 */
    widthFraction: Float? = null,
    // content 放最后：调用处用尾随 lambda 写法时必须对应最后一个参数
    content: @Composable ColumnScope.() -> Unit,
) {
    // v0.194.0：用主题背景明度判深浅（App 主题默认跟随系统），不用写死深色。
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val dialogBg = if (isDark) Color(0xFF171B28) else MaterialTheme.colorScheme.surface
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = if (widthFraction != null) DialogProperties(usePlatformDefaultWidth = false) else DialogProperties(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction ?: 1f)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(LocalBrandGradient.current))
                .padding(1.5.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(dialogBg)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Column {
                Text(
                    text = title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    // v0.194.0：深底青字、浅底深青（风格统一后取代金字）。
                    color = accentTitleColor(),
                )
                Spacer(Modifier.height(6.dp))
                // 标题下渐变细线（呼应顶栏品牌线）
                Box(
                    modifier = Modifier
                        .width(46.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(LocalBrandGradient.current)),
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // v0.194.0：内容区不再固定浅色，改跟主题——浅色主题下裸 Text
                // 拿 onSurface 深色正好可读，深色主题下拿浅色也可读。
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    content()
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissText != null) {
                        TextButton(onClick = onDismissRequest) {
                            Text(
                                dismissText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (onConfirm != null) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text(confirmText, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * v0.84.0：科技感输入框——深底 + 清晰描边，聚焦时描边变主题色。
 * 替代弹窗里的 OutlinedTextField（原来边框太淡、看不清）。
 */
@Composable
fun TechTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    // v0.213.0：Cookie 输入框遮掩用（默认不遮掩，其他调用方不受影响）
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (value.isEmpty() && placeholder.isNotBlank()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
            }
            inner()
        },
    )
}
