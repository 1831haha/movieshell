package com.movieshell.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftMotion
import com.movieshell.ui.theme.SoftType

/**
 * v0.27.0 Soft Tech 公共组件（单入口，各屏不许自建卡片/按钮）。
 */

/** 通用卡片：surface 底 + 1dp 低对比描边 + 20dp 圆角。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(SoftDimens.CardCorner)
    Surface(
        modifier = modifier
            .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        content = content,
    )
}

enum class AppButtonVariant { Primary, Secondary, Ghost }

/** 通用按钮：主/次/幽灵三档，16dp 圆角，触摸目标 48dp。可选前置图标。 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = when (variant) {
        AppButtonVariant.Primary -> ButtonDefaults.buttonColors()
        AppButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
        AppButtonVariant.Ghost -> ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        )
    }
    val shape = RoundedCornerShape(SoftDimens.ButtonCorner)
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = SoftType.Body)
    }
    if (variant == AppButtonVariant.Ghost) {
        TextButton(
            onClick = onClick,
            modifier = modifier.height(SoftDimens.TouchTarget),
            enabled = enabled,
            colors = colors,
            content = content,
        )
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier.height(SoftDimens.TouchTarget),
            enabled = enabled,
            shape = shape,
            colors = colors,
            content = content,
        )
    }
}

/** 胶囊筛选 chip。 */
@Composable
fun AppChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = SoftType.Caption) },
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(SoftDimens.ChipCorner),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = LocalSoftExtra.current.border,
            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            borderWidth = SoftDimens.BorderWidth,
        ),
    )
}

/** 分区标题：15sp Medium + 可选右侧操作。 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = SoftType.CardTitle, color = MaterialTheme.colorScheme.onSurface)
        action?.invoke()
    }
}

enum class EmptyKind { Empty, Error, Offline }

/** 三态空视图：空 / 加载失败 / 无网络。 */
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = SoftDimens.PagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = SoftType.CardTitle, color = MaterialTheme.colorScheme.onSurface)
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            AppButton(text = actionText, onClick = onAction, variant = AppButtonVariant.Secondary)
        }
    }
}

/** 骨架屏占位：柔和脉冲，不用生硬转菊花。 */
@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(SoftDimens.PosterCorner),
) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val alpha by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(SoftMotion.Fast * 2, easing = SoftMotion.Ease),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .alpha(alpha),
    )
}

/** 通用输入框：surfaceVariant 底 + 14dp 圆角。 */
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
    val shape = RoundedCornerShape(SoftDimens.FieldCorner)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = SoftType.Body.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (value.isEmpty() && placeholder.isNotBlank()) {
                Text(placeholder, style = SoftType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inner()
        },
    )
}
