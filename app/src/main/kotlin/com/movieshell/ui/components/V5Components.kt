package com.movieshell.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movieshell.ui.theme.LocalUnscaledDensity
import com.movieshell.ui.theme.V5
import com.movieshell.ui.theme.V5Motion
import com.movieshell.ui.theme.artBrush
import com.movieshell.ui.theme.artRing
import com.movieshell.ui.theme.isDark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.292.0 V5 Material You 共享组件（design-tokens.md 第 5 节）。
 *
 * 上一版组件层（平面 1px 分隔线风格）与更早的 Soft Tech 组件已整体删除，
 * 全部在这里按 M3 重做：颜色只取 MaterialTheme.colorScheme 角色，字阶取
 * MaterialTheme.typography，圆角取 [V5] 形状阶。各屏只用这里的件，不自建样式。
 * 函数行为（点击/长按/选中/无障碍）与旧组件一致，只换外观。
 */

/**
 * 底栏的内容留白：tab 页的滚动容器底部加上它（MainActivity 按底栏/迷你播放条
 * 是否显示提供；非 tab 页为 0）。
 */
val LocalDockClearance = compositionLocalOf { 0.dp }

/** 读当前底栏留白（tab 页滚动内容 contentPadding.bottom 用）。 */
@Composable
fun dockClearance(): Dp = LocalDockClearance.current

/** 1dp 分隔线（outlineVariant；strong = outline）。 */
@Composable
fun V5Divider(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                if (strong) MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
    )
}

/** 底部 1dp 分隔线 modifier（行、tab 条用）。 */
fun Modifier.v5BottomDivider(color: Color): Modifier = drawBehind {
    val w = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}

/**
 * M3 主要可滚动标签栏（48dp；标签 15sp 500；选中 primary + 3dp 圆头指示条，
 * 底部 1dp surfaceContainerHighest 分隔）。横向可滚动；selected 为 -1 时都不选中。
 * 自绘而不用 PrimaryScrollableTabRow：要保留长按回调与 -1 无选中态。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun V5TopTabs(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((Int) -> Unit)? = null,
    // v1.1.29：高度可调，默认 V5.Tabs（48dp）；首页收紧时传 40dp，音乐页不动。
    // 注意：必须放在 trailing 之前——Kotlin 尾随 lambda 绑的是最后一个参数，
    // 放后面会让音乐页那个尾随 Row 绑到 height 上（#601 编译失败的教训）。
    height: Dp = V5.Tabs,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .v5BottomDivider(cs.surfaceContainerHighest),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
        ) {
            titles.forEachIndexed { i, t ->
                val on = i == selected
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(
                            if (onLongClick != null) Modifier.combinedClickable(
                                onClick = { onSelect(i) },
                                onLongClick = { onLongClick(i) },
                            ) else Modifier.clickable(role = Role.Tab) { onSelect(i) },
                        )
                        .semantics { this.selected = on }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) cs.primary else cs.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (on) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .width(28.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(cs.primary),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing,
        )
    }
}

/**
 * M3 分段按钮（SingleChoiceSegmentedButtonRow 外观：outline 1dp 描边、整胶囊两端；
 * 选中 secondaryContainer + 勾）。用于 主题模式、直播 源1–6 等。
 */
@Composable
fun V5Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    showCheck: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    // v0.294.51：5 项及以上等分时每格只剩约 65dp，“订阅源”带勾（16dp+6dp）
    // 加 4 字文案必超宽被截成“订…/批量…”——紧凑模式下：内边距 8dp→4dp、
    // 字号 labelLarge→labelMedium、不画勾（选中底色已足够区分），
    // 4 字文案刚好完整显示；4 项及以下保持原样，不动其他屏。
    val compact = options.size >= 5
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .border(1.dp, cs.outline, shape),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(
                if (on) cs.secondaryContainer else Color.Transparent,
                tween(V5Motion.Fast, easing = V5Motion.Ease),
                label = "segBg",
            )
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(cs.outline))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(bg)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .semantics { this.selected = on }
                    .padding(horizontal = if (compact) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (on && showCheck && !compact) {
                    Icon(Icons.Filled.Check, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = if (on) cs.onSecondaryContainer else cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

enum class V5TagKind { Vip, Live, Accent, Neutral }

/** 小标签（labelSmall、6dp 圆角、容器色底）。 */
@Composable
fun V5Tag(text: String, kind: V5TagKind = V5TagKind.Vip, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (fg, bg) = when (kind) {
        V5TagKind.Vip -> cs.onTertiaryContainer to cs.tertiaryContainer
        V5TagKind.Live -> cs.onErrorContainer to cs.errorContainer
        V5TagKind.Accent -> cs.onPrimaryContainer to cs.primaryContainer
        V5TagKind.Neutral -> cs.onSurfaceVariant to cs.surfaceContainerHighest
    }
    Box(
        modifier
            .clip(RoundedCornerShape(V5.CornerXs))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 状态点（默认 6dp）。 */
@Composable
fun V5Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** M3 开关（52×32，开 = primary 轨道）。 */
@Composable
fun V5Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(),
    )
}

/**
 * 区块标题（48dp；20/28 500 onSurface + 计数 onSurfaceVariant + 右侧 TextButton 式操作 primary）。
 * 用于 继续观看 / 今日推荐 等分区。
 */
@Composable
fun V5SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    trailingChevron: Boolean = false,
    onAction: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = V5.SectionHeader),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title.isNotEmpty()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, letterSpacing = 0.sp),
                color = cs.onSurface,
                maxLines = 1,
            )
        }
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (actionText != null || actionIcon != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(if (onAction != null) Modifier.clickable(onClick = onAction) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (actionIcon != null) {
                    Icon(actionIcon, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                if (actionText != null) {
                    Text(actionText, style = MaterialTheme.typography.labelLarge, color = cs.primary, maxLines = 1)
                }
                if (trailingChevron) {
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Filled.ChevronRight, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * M3 列表项（56dp 起；前置 24dp onSurfaceVariant 图标；标题 bodyLarge，副标题 bodyMedium
 * onSurfaceVariant；右侧值 labelLarge + 箭头，或自定义尾巴（开关））。
 */
@Composable
fun V5Row(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    divider: Boolean = true,
    height: Dp = V5.ListItem,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .then(if (divider) Modifier.v5BottomDivider(cs.outlineVariant.copy(alpha = 0.5f)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = V5.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) Text(value, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1)
        if (trailing != null) trailing()
        else if (showChevron) Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
    }
}

/** M3 标准图标按钮（默认 40dp 圆形；filled = surfaceContainerHigh 底，tonal = secondaryContainer 底）。 */
@Composable
fun V5IconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    filled: Boolean = false,
    tonal: Boolean = false,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                when {
                    tonal -> Modifier.background(cs.secondaryContainer)
                    filled -> Modifier.background(cs.surfaceContainerHigh)
                    else -> Modifier
                },
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (tonal) cs.onSecondaryContainer else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * M3 搜索栏（收起态，点了进搜索页）：56dp、28dp 圆角、surfaceContainerHigh，
 * 前置放大镜 + bodyLarge 占位字。
 */
@Composable
fun V5SearchField(
    placeholder: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // v0.294.6：与 AppSearchBar 同尺寸同排布（48dp、放大镜 22dp onSurfaceVariant、文字起点一致）
    height: Dp = 48.dp,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            placeholder,
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 占位图（主题容器色柔和斜向渐变 + 细圆环 + 底部微暗）——没图/图挂时用，不用假海报。 */
@Composable
fun V5Art(
    seed: String,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(V5.CornerMd),
    ring: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val brush = cs.artBrush(seed)
    val ringColor = cs.artRing
    val dark = cs.isDark
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
            .drawBehind {
                if (ring) {
                    val r = size.minDimension * 0.30f
                    val h = (seed.hashCode() ushr 3) % 3
                    val c = when (h) {
                        0 -> Offset(size.width * 0.38f, size.height * 0.30f)
                        1 -> Offset(size.width * 0.62f, size.height * 0.40f)
                        else -> Offset(size.width * 0.55f, size.height * 0.35f)
                    }
                    drawCircle(ringColor, r, c)
                }
                drawRect(
                    Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (dark) 0.28f else 0.08f),
                    ),
                )
            },
        content = content,
    )
}

/**
 * 统计环（CircularProgressIndicator(progress) 外观：56dp、4dp 描边、圆头，
 * 轨道 surfaceContainerHighest，进度 [color]（默认 primary），中间 titleMedium 数值）。
 */
@Composable
fun V5RingGauge(
    value: String,
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 56.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val sw = 4.dp.toPx()
            val r = size.minDimension / 2 - sw / 2
            val tl = Offset(center.x - r, center.y - r)
            val sz = androidx.compose.ui.geometry.Size(r * 2, r * 2)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            val p = progress.coerceIn(0f, 1f)
            if (p > 0f) {
                drawArc(color, -90f, 360f * p, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** 卡片底（largeIncreased 20dp 圆角、默认 surfaceContainerLow，无描边）。 */
@Composable
fun V5Tile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    corner: Dp = V5.CornerLgI,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** 小号按钮（40dp 胶囊；primary = 填充主色，否则 FilledTonal secondaryContainer）。 */
@Composable
fun V5SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val fg = if (primary) cs.onPrimary else cs.onSecondaryContainer
    Row(
        modifier = modifier
            .height(V5.Button)
            .clip(RoundedCornerShape(50))
            .background(if (primary) cs.primary else cs.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/**
 * 出血：在有左右内边距的容器（如网格 contentPadding 16dp）里让某一项横向撑满屏宽，
 * 用于 tab 条/分隔线这类要通栏的元素。
 */
fun Modifier.v5Bleed(horizontal: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = (horizontal * 2).roundToPx()
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
        val placeable = measurable.measure(
            constraints.copy(minWidth = maxW.coerceAtLeast(0), maxWidth = maxW),
        )
        val w = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        layout(w, placeable.height) {
            placeable.place(-horizontal.roundToPx(), 0)
        }
    },
)

/**
 * M3 FilterChip（32dp、8dp 圆角；未选 outlineVariant 描边 + onSurfaceVariant 字；
 * 选中 secondaryContainer 底 + 前置勾 + onSecondaryContainer 字）。small = 28dp 二级筛选。
 */
@Composable
fun V5Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(V5.CornerSm)
    val fg = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant
    Row(
        modifier = modifier
            .height(if (small) 28.dp else V5.Chip)
            .clip(shape)
            .then(
                if (selected) Modifier.background(cs.secondaryContainer)
                else Modifier.border(1.dp, cs.outlineVariant, shape),
            )
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = if (small) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val lead = if (selected) Icons.Filled.Check else leadingIcon
        if (lead != null) {
            Icon(lead, null, tint = fg, modifier = Modifier.size(if (small) 16.dp else 18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = if (small) 13.sp else 14.sp),
            color = fg,
            maxLines = 1,
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(trailingIcon, null, tint = fg, modifier = Modifier.size(18.dp))
        }
    }
}

/** 分区标题（48dp 起；titleMedium onSurface + 可选右侧操作）。 */
@Composable
fun V5SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = V5.SectionHeader),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        action?.invoke()
    }
}

/** 底栏导航项。 */
data class V5NavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * M3 NavigationBar（design-tokens 第 5 节）：64dp、surfaceContainer 底；
 * 选中项 56×28 secondaryContainer 胶囊指示器 + 实心图标，标签 12sp（选中 onSurface 粗体，未选 onSurfaceVariant）。
 * 自绘而不用 NavigationBarItem：要保留首页 tab 长按刷新与切换轻触觉。
 * v1.1.15：未选中图标 tint 改 onSurface 提亮；选中 tab 图标切过来时跑一次
 * 流光（齿轮绕一圈、其余从下往上扫），跑完停，减动效时不跑。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun V5NavigationBar(
    items: List<V5NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // 288：长按首页 tab 刷新当前源（用户定案）——调用方按下标过滤；不传时保持原单击行为。
    onLongClick: ((Int) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    // v1.1.15：流光只在选中变化时跑一次——首帧不跑（进页不算切换），
    // 之后 selectedIndex 每变一次 token 加一，选中项的图标重跑一遍。
    var shineToken by remember { mutableStateOf(0) }
    var firstSelection by remember { mutableStateOf(true) }
    LaunchedEffect(selectedIndex) {
        if (firstSelection) {
            firstSelection = false
        } else {
            shineToken++
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(V5.NavBar)
            .background(cs.surfaceContainer)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val selected = i == selectedIndex
            val indicator by animateColorAsState(
                if (selected) cs.secondaryContainer else Color.Transparent,
                tween(V5Motion.Fast, easing = V5Motion.Ease),
                label = "navIndicator",
            )
            val click: () -> Unit = {
                if (!selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect(i)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (onLongClick != null) Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = click,
                            onLongClick = { onLongClick(i) },
                        ) else Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = click,
                        ),
                    )
                    .semantics {
                        role = Role.Tab
                        this.selected = selected
                        contentDescription = item.label
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(indicator),
                    contentAlignment = Alignment.Center,
                ) {
                    NavTabIcon(
                        item = item,
                        selected = selected,
                        tint = if (selected) cs.onSecondaryContainer else cs.onSurface,
                        shineToken = shineToken,
                        reducedMotion = reducedMotion,
                    )
                }
                Spacer(Modifier.height(2.dp))
                // v1.1.26：底栏标签就是全 App 字号的参照（12sp），不跟 ×0.8。
                CompositionLocalProvider(LocalDensity provides LocalUnscaledDensity.current) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** v1.1.15：系统减动效——照抄音乐播放页 rememberReducedMotion（v0.248.0）：
 * 用平台标准接口（动画时长缩放为 0 或无障碍移除动画时均返回 false），
 * 进页读一次、每次回到前台重读；开启时底栏流光全部静态，图标只提亮。 */
@Composable
private fun rememberReducedMotion(): Boolean {
    var reducedMotion by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return reducedMotion
}

/**
 * v1.1.17：底栏 tab 图标（含选中动效）。
 * 选中变化时跑一次：小球顺时针沿各图标的字形轮廓走一圈（约 2.2 秒，
 * FastOutSlowIn 先快后慢、转完放缓停下），光尾跟着球长到接成一整
 * 圈，再整体约 0.6 秒慢慢变暗消失；三个图标有收尾彩蛋（书架描书
 * 签、AI 眼睛亮、齿轮外圈亮）。进页首帧不跑，点已选中的 tab 不重
 * 跑；减动效时不跑，图标只提亮、静态。
 * 齿轮选中态用镂空齿轮（Outlined.Settings，中间透），小球沿齿轮
 * 内圈（半径 4.2dp）转；其余五个小球沿各自字形轮廓走，轨道按字
 * 形边缘内收约 1dp，画在图标上层。颜色是炫彩多色、不跟主题色：
 * 小球色相随转圈流转（彩虹色，2 圈色相走完一整圈，收尾同色无
 * 缝），透明度脉动（0.25~1，每圈约 2.5 次）；光尾同样是彩虹色，
 * 沿轨道长度方向色相渐变。球径：首页/直播/书架 0.75dp、音乐
 * 0.65dp、AI 0.7dp、齿轮 0.85dp；光尾宽 0.75dp；光晕约球半径
 * 的 1.6 倍。
 */
// v1.1.17：收尾彩蛋种类——无、书签、眼睛、齿轮外圈。
private enum class FinaleKind { NONE, RIBBON, EYES, GEAR }

// v1.1.17：小球轨道规格——球半径；轨道 Path 构造器；书签彩蛋的
// 书签 Path 构造器（只书架有，默认空 Path）；收尾彩蛋种类。
// 所有轨道 Path 都按 24 分坐标写，构造器入参 u 为每单位对应的 px 数。
private data class OrbitSpec(
    val core: Dp,
    val buildOrbit: (Float) -> Path,
    val buildRibbon: (Float) -> Path = { Path() },
    val finale: FinaleKind = FinaleKind.NONE,
)

// v1.1.17：圆角矩形轨道（起点为顶边中点，顺时针）。left/top/
// right/bottom 为 24 分坐标，r 为圆角半径。
private fun rectOrbitPath(u: Float, left: Float, top: Float, right: Float, bottom: Float, r: Float): Path {
    val cx = (left + right) / 2f
    return Path().apply {
        moveTo(cx * u, top * u)
        lineTo((right - r) * u, top * u)
        quadraticBezierTo(right * u, top * u, right * u, (top + r) * u)
        lineTo(right * u, (bottom - r) * u)
        quadraticBezierTo(right * u, bottom * u, (right - r) * u, bottom * u)
        lineTo((left + r) * u, bottom * u)
        quadraticBezierTo(left * u, bottom * u, left * u, (bottom - r) * u)
        lineTo(left * u, (top + r) * u)
        quadraticBezierTo(left * u, top * u, (left + r) * u, top * u)
        close()
    }
}

// v1.1.17：首页轨道——内收后的房子多边形（屋顶顶点 12,4.34、
// 屋檐墙 x=6/18、底 y=19），屋檐与底角微圆角，顺时针。
private fun homeOrbitPath(u: Float): Path = Path().apply {
    moveTo(12f * u, 4.34f * u)
    lineTo(17.4f * u, 9.2f * u)
    quadraticBezierTo(18f * u, 9.75f * u, 18f * u, 10.5f * u)
    lineTo(18f * u, 18.2f * u)
    quadraticBezierTo(18f * u, 19f * u, 17.2f * u, 19f * u)
    lineTo(6.8f * u, 19f * u)
    quadraticBezierTo(6f * u, 19f * u, 6f * u, 18.2f * u)
    lineTo(6f * u, 10.5f * u)
    quadraticBezierTo(6f * u, 9.75f * u, 6.6f * u, 9.2f * u)
    close()
}

// v1.1.17：音乐轨道——沿音符轮廓走（旗块 x=12..18、y=3..7，符
// 干 x=12..14，符头椭圆中心 10,17.55、rx=4、ry=4），顺时针；头
// 部一段收进轮廓内，不外鼓出 x=14。
private fun musicOrbitPath(u: Float): Path = Path().apply {
    moveTo(13f * u, 4.2f * u)
    lineTo(16.8f * u, 4.2f * u)
    quadraticBezierTo(17.6f * u, 4.2f * u, 17.6f * u, 5f * u)
    lineTo(17.6f * u, 5.8f * u)
    quadraticBezierTo(17.6f * u, 6.6f * u, 16.8f * u, 6.6f * u)
    lineTo(13.6f * u, 6.6f * u)
    lineTo(13.6f * u, 13.6f * u)
    cubicTo(13.8f * u, 14.8f * u, 13.6f * u, 17.2f * u, 12.6f * u, 19.2f * u)
    cubicTo(11f * u, 21.2f * u, 8.4f * u, 21f * u, 7.4f * u, 19.2f * u)
    cubicTo(6.4f * u, 17.4f * u, 7.6f * u, 15f * u, 9.8f * u, 14.4f * u)
    cubicTo(10.8f * u, 14.1f * u, 11.7f * u, 14.3f * u, 12.4f * u, 14.7f * u)
    lineTo(12.4f * u, 4.2f * u)
    close()
}

// v1.1.17：齿轮内圈轨道——半径 4.2dp 的圆（24 分坐标下半径
// 4.2*24/22），起点为顶部，顺时针。
private fun gearOrbitPath(u: Float): Path {
    val r = 4.2f * 24f / 22f * u
    val cx = 12f * u
    val cy = 12f * u
    return Path().apply {
        moveTo(cx, cy - r)
        arcTo(Rect(cx - r, cy - r, cx + r, cy + r), -90f, 180f, false)
        arcTo(Rect(cx - r, cy - r, cx + r, cy + r), 90f, 180f, false)
    }
}

// v1.1.17：书签彩蛋的书签 Path（24 分坐标，顺时针）。
private fun bookmarkPath(u: Float): Path = Path().apply {
    moveTo(9.3f * u, 4.4f * u)
    lineTo(10.7f * u, 4.4f * u)
    lineTo(10.7f * u, 8.4f * u)
    lineTo(10f * u, 7.9f * u)
    lineTo(9.3f * u, 8.4f * u)
    close()
}

// v1.1.17：齿轮外形上的点——r 为半径（24 分坐标），deg 为从顶
// 部起顺时针的角度。
private fun gearPoint(r: Float, deg: Float, u: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    return Offset((12f + r * sin(a)) * u, (12f - r * cos(a)) * u)
}

// v1.1.17：齿轮外圈 Path（24 分坐标，供彩蛋描边用）：8 齿，每
// 齿齿顶 20°、齿顶半径 8.8、齿根半径 7.0，齿顶与齿根之间直线连
// 接成多边形；中心孔半径 3.6 另画圆。
private fun gearOutlinePath(u: Float): Path {
    val path = Path()
    for (i in 0 until 8) {
        val a = i * 45f
        val tipStart = gearPoint(8.8f, a + 12.5f, u)
        val tipEnd = gearPoint(8.8f, a + 32.5f, u)
        val valleyEnd = gearPoint(7.0f, a + 57.5f, u)
        if (i == 0) path.moveTo(tipStart.x, tipStart.y) else path.lineTo(tipStart.x, tipStart.y)
        path.lineTo(tipEnd.x, tipEnd.y)
        path.lineTo(valleyEnd.x, valleyEnd.y)
    }
    path.close()
    return path
}

// v1.1.17：按选中图标（selectedIcon）认 tab。球径：首页/直播/
// 书架 0.75dp、音乐 0.65dp（字形最窄，球最小）、AI 0.7dp、齿
// 轮 0.85dp；彩蛋：书架描书签、AI 眼睛亮、齿轮外圈亮。
private fun orbitSpec(item: V5NavItem): OrbitSpec = when (item.selectedIcon) {
    Icons.Filled.Settings -> OrbitSpec(0.85.dp, ::gearOrbitPath, finale = FinaleKind.GEAR)
    Icons.Filled.Home -> OrbitSpec(0.75.dp, ::homeOrbitPath)
    Icons.Filled.LiveTv -> OrbitSpec(0.75.dp, { u -> rectOrbitPath(u, 4f, 9f, 20f, 19f, 1f) })
    Icons.Filled.Book -> OrbitSpec(0.75.dp, { u -> rectOrbitPath(u, 5f, 3f, 19f, 21f, 1f) }, ::bookmarkPath, FinaleKind.RIBBON)
    Icons.Filled.MusicNote -> OrbitSpec(0.65.dp, ::musicOrbitPath)
    Icons.Filled.SmartToy -> OrbitSpec(0.7.dp, { u -> rectOrbitPath(u, 5f, 8f, 19f, 19f, 2f) }, finale = FinaleKind.EYES)
    else -> OrbitSpec(0.75.dp, ::homeOrbitPath)
}

@Composable
private fun NavTabIcon(
    item: V5NavItem,
    selected: Boolean,
    tint: Color,
    shineToken: Int,
    reducedMotion: Boolean,
) {
    // 设置 tab 认图标本身（Outlined.Settings / Filled.Settings），不认下标。
    val isGear = item.icon == Icons.Outlined.Settings ||
        item.selectedIcon == Icons.Filled.Settings
    // v1.1.17：两段动效——轨道 0→1（约 2.2 秒，FastOutSlowIn），
    // 再接彩蛋＋变暗 0→1（700ms 彩蛋＋600ms 变暗，线性）。
    val orbit = remember { Animatable(0f) }
    val post = remember { Animatable(0f) }
    LaunchedEffect(shineToken) {
        if (shineToken == 0 || !selected || reducedMotion) return@LaunchedEffect
        orbit.snapTo(0f)
        post.snapTo(0f)
        orbit.animateTo(1f, tween(2200, easing = FastOutSlowInEasing))
        post.animateTo(1f, tween(1300, easing = LinearEasing))
    }
    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        // v1.1.16：齿轮选中态也用镂空齿轮（Outlined.Settings），实心齿
        // 轮会把里面的小球盖住；未选中态本来就是 Outlined，和原来一样。
        Icon(
            imageVector = if (isGear) Icons.Outlined.Settings
            else if (selected) item.selectedIcon else item.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        // 轨道没跑完、或彩蛋＋变暗没跑完之前，一直绘制；跑完即停。
        val o = orbit.value
        val p = post.value
        if (selected && !reducedMotion && ((o > 0f && o < 1f) || (o >= 1f && p < 1f))) {
            Canvas(Modifier.size(22.dp)) {
                val spec = orbitSpec(item)
                val u = size.width / 24f
                val center = Offset(size.width / 2f, size.height / 2f)
                val corePx = spec.core.toPx()
                val tailWidthPx = 0.75.dp.toPx()
                val orbitPath = spec.buildOrbit(u)
                val measure = PathMeasure().apply { setPath(orbitPath, false) }
                val totalLen = measure.length

                // 总时间轴（毫秒）：轨道 2200 + 彩蛋 700 + 变暗 600。
                val elapsedMs = o * 2200f + p * 1300f
                // 彩蛋进度（post 前 700ms）与变暗进度（post 后 600ms）。
                val finaleProgress = (p * 1300f / 700f).coerceIn(0f, 1f)
                val fadeProgress = ((p * 1300f - 700f) / 600f).coerceIn(0f, 1f)
                val fadeAlpha = 1f - fadeProgress
                // 彩蛋里的取色色相：沿时间接着轨道末尾的色相走。
                val finaleHue = (elapsedMs / 2200f * 360f * 2f) % 360f
                // 透明度脉动：0.25~1，每圈约 2.5 次，彩蛋阶段接着走。
                val pulseAlpha = (0.25f + 0.75f * abs(sin(elapsedMs / 2200f * PI.toFloat() * 2.5f))) * fadeAlpha
                // 沿 Path 从 0 画到 trailLen 的彩虹光尾，分 48 段，每段
                // 色相按其在轨道上的位置取，圆头收尾。
                fun rainbowTrail(m: PathMeasure, len: Float, trailLen: Float, alpha: Float, hueCycles: Float, widthPx: Float) {
                    if (trailLen <= 0f || alpha <= 0f || len <= 0f) return
                    val segments = 48
                    val step = trailLen / segments
                    var prev = m.getPosition(0f)
                    for (i in 1..segments) {
                        val d = (step * i).coerceAtMost(trailLen)
                        val pos = m.getPosition(d)
                        val mid = (d + step * (i - 1)) / 2f
                        val hue = ((mid / len) * 360f * hueCycles) % 360f
                        drawLine(
                            color = Color.hsv(hue, 0.95f, 1f).copy(alpha = alpha),
                            start = prev,
                            end = pos,
                            strokeWidth = widthPx,
                            cap = StrokeCap.Round,
                        )
                        prev = pos
                    }
                }

                // 小球位置与色相：轨道阶段沿轨道走；彩蛋阶段书架去描
                // 书签，其余停在圈顶；光尾先画，球头压在最上层。
                var ballPos: Offset
                var ballHue: Float
                if (o < 1f) {
                    val d = o * totalLen
                    ballPos = measure.getPosition(d)
                    ballHue = ((d / totalLen) * 360f * 2f) % 360f
                    rainbowTrail(measure, totalLen, d, fadeAlpha, 2f, tailWidthPx)
                } else {
                    rainbowTrail(measure, totalLen, totalLen, fadeAlpha, 2f, tailWidthPx)
                    when (spec.finale) {
                        FinaleKind.RIBBON -> {
                            val ribbonPath = spec.buildRibbon(u)
                            val ribbonMeasure = PathMeasure().apply { setPath(ribbonPath, false) }
                            val ribbonLen = ribbonMeasure.length
                            val rd = (finaleProgress * ribbonLen).coerceAtMost(ribbonLen)
                            ballPos = ribbonMeasure.getPosition(rd)
                            ballHue = if (ribbonLen > 0f) ((rd / ribbonLen) * 360f) % 360f else 0f
                            rainbowTrail(ribbonMeasure, ribbonLen, rd, fadeAlpha, 1f, tailWidthPx)
                        }
                        FinaleKind.EYES -> {
                            ballPos = measure.getPosition(totalLen)
                            ballHue = 0f
                            val eyeAlpha = minOf(1f, finaleProgress * 3f) * fadeAlpha
                            val eyeRPx = 1.4.dp.toPx()
                            listOf(Offset(9f * u, 11.5f * u), Offset(15f * u, 11.5f * u)).forEach { eye ->
                                drawCircle(Color.hsv(finaleHue, 0.95f, 1f).copy(alpha = 0.3f * eyeAlpha), radius = eyeRPx * 1.6f, center = eye)
                                drawCircle(Color.hsv(finaleHue, 0.95f, 1f).copy(alpha = eyeAlpha), radius = eyeRPx, center = eye)
                            }
                        }
                        FinaleKind.GEAR -> {
                            ballPos = measure.getPosition(totalLen)
                            ballHue = 0f
                            val gearAlpha = minOf(1f, finaleProgress * 3f) * fadeAlpha
                            val gearPath = gearOutlinePath(u)
                            val gearMeasure = PathMeasure().apply { setPath(gearPath, false) }
                            // 光晕：宽描边淡色打底，再描彩虹色。
                            drawPath(
                                path = gearPath,
                                color = Color.hsv(finaleHue, 0.95f, 1f).copy(alpha = 0.15f * gearAlpha),
                                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                            rainbowTrail(gearMeasure, gearMeasure.length, gearMeasure.length, gearAlpha, 1f, 1.5.dp.toPx())
                            drawCircle(
                                color = Color.hsv(finaleHue, 0.95f, 1f).copy(alpha = gearAlpha),
                                radius = 3.6f * u,
                                center = center,
                                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }
                        FinaleKind.NONE -> {
                            ballPos = measure.getPosition(totalLen)
                            ballHue = 0f
                        }
                    }
                }
                drawCircle(Color.hsv(ballHue, 0.95f, 1f).copy(alpha = 0.35f * pulseAlpha), radius = corePx * 1.6f, center = ballPos)
                drawCircle(Color.hsv(ballHue, 0.95f, 1f).copy(alpha = pulseAlpha), radius = corePx, center = ballPos)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 通用卡片 / 按钮 / 空态 / 骨架 / 输入框（原 Soft Tech 组件，V5 按 M3 重做，签名不变）
// ---------------------------------------------------------------------------

/** 通用卡片：M3 filled card（surfaceContainerLow 底、16dp 圆角、无描边）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    // v0.196.0：长按回调（书源卡片长按编辑）；给了才用 combinedClickable，否则沿用 clickable
    onLongClick: (() -> Unit)? = null,
    // v0.78.2：允许自定义形状（如详情页信息卡只要顶部圆角，与播放器衔接）
    shape: RoundedCornerShape = RoundedCornerShape(V5.CornerLg),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(shape)
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        // v0.27.1：内容纵向排。Surface 默认是 Box，多 children 会叠在一起。
        Column { content() }
    }
}

enum class AppButtonVariant { Primary, Secondary, Ghost, Cyan }

/**
 * 通用按钮（M3）：Primary/Cyan = Filled，Secondary = FilledTonal，Ghost = Text。
 * 40dp 胶囊、labelLarge；可选前置 18dp 图标。
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    // v1.1.20：紧凑模式（默认关）——高 40dp 降到 36dp、横向内边距收到 8dp、
    // 字号降到 labelMedium（12sp），窄按钮（如写源类型里的“Python”）不再截字。
    compact: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val colors = when (variant) {
        AppButtonVariant.Primary, AppButtonVariant.Cyan -> ButtonDefaults.buttonColors(
            containerColor = cs.primary,
            contentColor = cs.onPrimary,
        )
        AppButtonVariant.Secondary -> ButtonDefaults.filledTonalButtonColors(
            containerColor = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
        )
        AppButtonVariant.Ghost -> ButtonDefaults.textButtonColors(
            contentColor = cs.primary,
        )
    }
    val shape = RoundedCornerShape(50)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
    val pad = PaddingValues(
        horizontal = if (compact) 8.dp else if (variant == AppButtonVariant.Ghost) 12.dp else 20.dp,
    )
    if (variant == AppButtonVariant.Ghost) {
        TextButton(
            onClick = onClick,
            modifier = modifier.height(if (compact) 36.dp else V5.Button),
            enabled = enabled,
            shape = shape,
            colors = colors,
            contentPadding = pad,
            content = content,
        )
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier.height(if (compact) 36.dp else V5.Button),
            enabled = enabled,
            shape = shape,
            colors = colors,
            contentPadding = pad,
            content = content,
        )
    }
}

enum class EmptyKind { Empty, Error, Offline }

/** 三态空视图（72dp 28 圆角图标井 + titleMedium 标题 + bodyMedium 提示 + FilledTonal 按钮）。 */
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
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = V5.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(V5.CornerXl))
                .background(if (kind == EmptyKind.Error) cs.errorContainer else cs.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (kind == EmptyKind.Error) cs.onErrorContainer else cs.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            AppButton(text = actionText, onClick = onAction, variant = AppButtonVariant.Secondary)
        }
    }
}

/** 骨架屏占位：surfaceContainerHigh 柔和脉冲，不用生硬转菊花。 */
@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(V5.CornerMd),
) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val alpha by t.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(V5Motion.Fast * 3, easing = V5Motion.Ease),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .alpha(alpha),
    )
}

/**
 * 通用输入框（surfaceContainerHighest 底、12dp 圆角、bodyLarge）。
 * v0.294.52：根因是弹窗底与输入框底同为 surfaceContainerHigh、无描边，
 * 框直接融进背景（订阅弹窗在深浅主题下都看不清）。从本版起一律描边：
 * 平时 1dp outline、聚焦 2dp 主色，底色与弹窗底错开一档；bordered 参数
 * 仅为调用处兼容保留，不再决定是否描边（原来只在通用网页源弹窗传 true）。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    /** v0.294.3：输入框右侧单位（如“秒”），空则不显示。 */
    suffix: String? = null,
    /** v0.294.52 起不再使用：全部输入框一律描边，见上方说明。 */
    @Suppress("UNUSED_PARAMETER")
    bordered: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(V5.CornerMd)
    // v0.294.3：有值时占位提示缩成框内顶部小标签——原来一填值提示就消失，
    // 预填的框（如跳过设置的两个 0）看不出各是什么；纯数字占位（如 "0"）不当标签。
    val floatLabel = value.isNotEmpty() && placeholder.isNotBlank() && placeholder.any { !it.isDigit() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
        cursorBrush = SolidColor(cs.primary),
        interactionSource = interactionSource,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .clip(shape)
            .background(cs.surfaceContainerHighest)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) cs.primary else cs.outline,
                shape = shape,
            )
            .padding(horizontal = 16.dp, vertical = if (floatLabel) 6.dp else 12.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (floatLabel) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        if (value.isEmpty() && placeholder.isNotBlank()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                        }
                        inner()
                    }
                }
                if (!suffix.isNullOrBlank()) {
                    Text(
                        suffix,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
    )
}

/** 让子内容取 onSurfaceVariant 作默认内容色（顶栏操作区等）。 */
@Composable
fun V5ActionsColor(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant, content = content)
}

