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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftMotion
import com.movieshell.ui.theme.SoftType
import com.movieshell.ui.theme.V4
import com.movieshell.ui.theme.V4Type
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min

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
    shape: RoundedCornerShape = RoundedCornerShape(SoftDimens.CardCorner),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .border(SoftDimens.BorderWidth, V4.c.div, shape)
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

/**
 * 筛选 chip（V4：6dp 圆角矩形，s1 底 t2 字；选中强调色 12% 底 + 强调色字，无描边无阴影）。
 * 深浅两套都走 V4 token。
 */
@Composable
fun AppChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false, // v0.30.1：二级筛选行用小一号
) {
    val v = V4.c
    Box(
        modifier = modifier
            .height(if (small) 26.dp else 28.dp)
            .clip(RoundedCornerShape(V4.RPoster))
            .background(if (selected) v.accentA else v.s1)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = if (small) 9.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = if (small) 11.5.sp else 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) v.accent else v.t2,
            maxLines = 1,
        )
    }
}

/**
 * v0.109.0 A9：强调标题色。
 * v0.279.0 V4：分区标题一律主文字色 t1（V4 只有一个强调色，且只给选中/主操作用）。
 */
@Composable
fun accentTitleColor(): Color = V4.c.t1

/** 分区标题（V4：32dp 高，12sp 600 t1 + 可选右侧操作）。 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = V4.SectionHeader),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = V4Type.Section, color = V4.c.t1)
        action?.invoke()
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
            .padding(vertical = 48.dp, horizontal = SoftDimens.PagePadding),
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
    shape: RoundedCornerShape = RoundedCornerShape(SoftDimens.PosterCorner),
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

/** 悬浮 dock 导航项。 */
data class SoftDockItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * v0.279.0 V4 · NAV B：居中浮动胶囊底栏。
 *
 * - 高 48dp、整胶囊圆角、内边距 4、项间距 4；固定总宽 334dp（屏窄时按屏宽 − 24dp 收）。
 * - 选中项固定 84dp 宽强调色药丸（图标 + 文字），未选中 44dp 只有图标；
 *   药丸是一个整体在底层平移（220ms 强调减速），各项宽度同步 44↔84，底栏总宽不变。
 * - 文字 80ms 后淡入 + 左移 4dp；按下缩 0.94；切换时轻触觉。
 * - 未选中无文字，每项带无障碍描述（tab 名）。
 * - Compose 1.6 没有背景模糊：用 95% 不透明的 #161921 底（[V4Colors.navBg]）。
 * 调用方负责把它摆在内容之上（MainActivity 里浮在 NavHost 上方，不占内容高度）。
 */
@Composable
fun SoftBottomDock(
    items: List<SoftDockItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val v = V4.c
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = V4.NavBottom),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val n = items.size.coerceAtLeast(1)
        val gap = 4.dp
        val pad = 4.dp
        val border = 1.dp
        val barWidth = min(V4.NavWidth, maxWidth - 24.dp)
        val activeW = V4.NavItemActive
        // 屏窄时只压未选中项；正常屏 = 44dp
        val inactiveW = ((barWidth - border * 2 - pad * 2 - gap * (n - 1) - activeW) / (n - 1).coerceAtLeast(1))
            .coerceAtMost(V4.NavItem)
        val pillX by animateDpAsState(
            targetValue = (inactiveW + gap) * selectedIndex.coerceIn(0, n - 1),
            animationSpec = tween(SoftMotion.Fast, easing = SoftMotion.Ease),
            label = "navPillX",
        )
        val shape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(V4.NavHeight)
                .shadow(
                    elevation = 14.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                )
                .clip(shape)
                .background(v.navBg)
                .border(border, v.navBorder, shape)
                .drawBehind {
                    // 顶部 1px 内高光（rgba(255,255,255,.04)）
                    if (v.isDark) {
                        drawLine(
                            color = Color.White.copy(alpha = 0.04f),
                            start = Offset(size.height / 2, 1.dp.toPx()),
                            end = Offset(size.width - size.height / 2, 1.dp.toPx()),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
                .padding(pad),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 底层：一个整体平移的强调色药丸
            Box(
                modifier = Modifier
                    .offset(x = pillX)
                    .width(activeW)
                    .height(40.dp)
                    .shadow(
                        elevation = 6.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = v.accent.copy(alpha = 0.32f),
                        spotColor = v.accent.copy(alpha = 0.32f),
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(v.accent),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { i, item ->
                    val selected = i == selectedIndex
                    val w by animateDpAsState(
                        targetValue = if (selected) activeW else inactiveW,
                        animationSpec = tween(SoftMotion.Fast, easing = SoftMotion.Ease),
                        label = "navItemW",
                    )
                    val fg by animateColorAsState(
                        targetValue = if (selected) v.onAccent else v.t2,
                        animationSpec = tween(SoftMotion.Fast, easing = SoftMotion.Ease),
                        label = "navItemFg",
                    )
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(
                        targetValue = if (pressed) 0.94f else 1f,
                        animationSpec = tween(90),
                        label = "navPress",
                    )
                    Box(
                        modifier = Modifier
                            .width(w)
                            .height(40.dp)
                            .scale(scale)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                            ) {
                                if (!selected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                onSelect(i)
                            }
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                                contentDescription = item.label
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = null,
                                tint = fg,
                                modifier = Modifier.size(19.dp),
                            )
                            AnimatedVisibility(
                                visible = selected,
                                enter = fadeIn(tween(120, delayMillis = 80)) +
                                    slideInHorizontally(tween(120, delayMillis = 80)) { -it / 6 },
                                exit = fadeOut(tween(80)),
                            ) {
                                Row {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        item.label,
                                        style = V4Type.NavLabel,
                                        color = v.onAccent,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
