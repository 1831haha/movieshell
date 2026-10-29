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
import com.movieshell.ui.theme.v4Dark
import com.movieshell.ui.theme.v4Light
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
    /** v0.245.0：强制深浅；null=跟系统主题（其他页面默认），阅读页传
     * 阅读背景的实际深浅，系统浅色时夜间黑阅读页的弹窗也不再发白。 */
    forceDark: Boolean? = null,
    // content 放最后：调用处用尾随 lambda 写法时必须对应最后一个参数
    content: @Composable ColumnScope.() -> Unit,
) {
    // v0.194.0：用主题背景明度判深浅（App 主题默认跟随系统），不用写死深色。
    // v0.245.0：forceDark 非空时按它走，并把整套配色换成对应深浅的副本，
    // 否则系统浅色下深底弹窗里的字会是深色、看不见。
    val systemDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val isDark = forceDark ?: systemDark
    val baseScheme = MaterialTheme.colorScheme
    val effectiveScheme = if (forceDark == null || forceDark == systemDark) {
        baseScheme
    } else if (isDark) {
        // v0.279.0 V4：强制深浅的副本取 V4 深/浅 token，不再另写一套色值。
        val d = v4Dark(baseScheme.primary)
        baseScheme.copy(
            background = d.bg,
            surface = d.s1,
            surfaceVariant = d.s2,
            onSurface = d.t1,
            onSurfaceVariant = d.t2,
            outline = d.div2,
            primary = d.accent,
            onPrimary = d.onAccent,
        )
    } else {
        val l = v4Light()
        baseScheme.copy(
            background = l.bg,
            surface = l.s1,
            surfaceVariant = l.s2,
            onSurface = l.t1,
            onSurfaceVariant = l.t2,
            outline = l.div2,
        )
    }
    val panel = if (isDark) v4Dark(baseScheme.primary) else v4Light()
    val dialogBg = if (isDark) panel.s1 else effectiveScheme.surface
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = if (widthFraction != null) DialogProperties(usePlatformDefaultWidth = false) else DialogProperties(),
    ) {
        MaterialTheme(
            colorScheme = effectiveScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction ?: 1f)
                // v0.279.0 V4：平面弹窗——s1 底 + 1px 分隔线描边 + 12dp 圆角，去掉渐变描边。
                .clip(RoundedCornerShape(12.dp))
                .background(dialogBg)
                .border(1.dp, panel.div2, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Column {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    // v0.279.0 V4：标题用主文字色，不再青字 + 渐变短线。
                    color = MaterialTheme.colorScheme.onSurface,
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
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(36.dp),
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
    val shape = RoundedCornerShape(8.dp)
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
