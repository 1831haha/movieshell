package com.movieshell.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.ui.theme.SoftMotion
import com.movieshell.ui.theme.V4
import com.movieshell.ui.theme.V4Type
import androidx.compose.ui.semantics.contentDescription

/**
 * v0.27.0 Soft Tech 公共组件（单入口，各屏不许自建卡片/按钮）。
 * v0.279.0 V4：全部换成 V4 口径——平面、1px 分隔线、6–8dp 圆角、唯一强调色。
 * 函数签名不变，老调用点自动换皮。
 */

/** 通用卡片：V4 s1 实底 + 1px 分隔线描边 + 8dp 圆角。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    // v0.196.0：长按回调（书源卡片长按编辑）；给了才用 combinedClickable，否则沿用 clickable
    onLongClick: (() -> Unit)? = null,
    // v0.78.2：允许自定义形状（如详情页信息卡只要顶部圆角，与播放器衔接）
    shape: RoundedCornerShape = RoundedCornerShape(V4.RField),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .border(1.dp, V4.c.div, shape)
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                }
            ),
        shape = shape,
        color = V4.c.s1,
        tonalElevation = 0.dp,
    ) {
        // v0.27.1：内容纵向排。Surface 默认是 Box，多 children 会叠在一起
        //（曾导致设置页"运行日志悬浮窗"行被下一行盖住、主题色行与色圈重叠）。
        Column { content() }
    }
}

enum class AppButtonVariant { Primary, Secondary, Ghost, Cyan }

/**
 * 通用按钮：主/次/幽灵三档 + Cyan（v0.279.0 V4 起与主按钮同款强调色，保留枚举不删）。
 * V4：高 36dp、8dp 圆角、12.5sp 600。可选前置图标。
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val v = V4.c
    val colors = when (variant) {
        AppButtonVariant.Primary, AppButtonVariant.Cyan -> ButtonDefaults.buttonColors(
            containerColor = v.accent,
            contentColor = v.onAccent,
            disabledContainerColor = v.accent.copy(alpha = 0.35f),
            disabledContentColor = v.onAccent.copy(alpha = 0.6f),
        )
        AppButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = v.s2,
            contentColor = v.t1,
            disabledContainerColor = v.s2,
            disabledContentColor = v.t3,
        )
        AppButtonVariant.Ghost -> ButtonDefaults.textButtonColors(
            contentColor = v.accent,
        )
    }
    val shape = RoundedCornerShape(V4.RField)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
    if (variant == AppButtonVariant.Ghost) {
        TextButton(
            onClick = onClick,
            modifier = modifier.height(36.dp),
            enabled = enabled,
            shape = shape,
            colors = colors,
            contentPadding = pad,
            content = content,
        )
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier.height(36.dp),
            enabled = enabled,
            shape = shape,
            colors = colors,
            contentPadding = pad,
            content = content,
        )
    }
}

enum class EmptyKind { Empty, Error, Offline }

/** 三态空视图（V4：56dp 圆角图标井 + 14/600 标题 + t3 提示 + 次按钮）。 */
@Composable
fun EmptyState(
    kind: EmptyKind,
    icon: ImageVector,
    title: String,
    hint: String = "",
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val v = V4.c
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = V4.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(V4.RWell))
                .background(v.s1),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (kind == EmptyKind.Error) v.live else v.t2,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = v.t1)
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                fontSize = 11.5.sp,
                color = v.t3,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            AppButton(text = actionText, onClick = onAction, variant = AppButtonVariant.Secondary)
        }
    }
}

/** 骨架屏占位：柔和脉冲，不用生硬转菊花。 */
@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(V4.RPoster),
) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val alpha by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(SoftMotion.Fast * 3, easing = SoftMotion.Ease),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(V4.c.s2)
            .alpha(alpha),
    )
}

/** 通用输入框（V4：s1 底、8dp 圆角、无描边，12.5sp）。 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val v = V4.c
    val shape = RoundedCornerShape(V4.RField)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = V4Type.Body.copy(color = v.t1),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(v.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .clip(shape)
            .background(v.s1)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        decorationBox = { inner ->
            if (value.isEmpty() && placeholder.isNotBlank()) {
                Text(placeholder, style = V4Type.Body, color = v.t3)
            }
            inner()
        },
    )
}
