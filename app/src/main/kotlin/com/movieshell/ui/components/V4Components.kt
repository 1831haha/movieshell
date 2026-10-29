package com.movieshell.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.ui.theme.SoftMotion
import com.movieshell.ui.theme.V4
import com.movieshell.ui.theme.V4Type
import com.movieshell.ui.theme.artBrush

/**
 * v0.279.0 V4 共享组件：顶部文字 tab、分段、标签、状态点、行、开关、环形仪表、
 * 图标按钮、占位图、底栏留白。各屏只用这里的件，不自建样式。
 */

/**
 * 浮动底栏的内容留白：tab 页的滚动容器底部加上它（MainActivity 按底栏/迷你播放条
 * 是否显示提供；非 tab 页为 0）。
 */
val LocalDockClearance = compositionLocalOf { 0.dp }

/** 读当前底栏留白（tab 页滚动内容 contentPadding.bottom 用）。 */
@Composable
fun dockClearance(): Dp = LocalDockClearance.current

/** 1px 分隔线。 */
@Composable
fun V4Divider(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (strong) V4.c.div2 else V4.c.div),
    )
}

/** 底部 1px 分隔线 modifier（行、tab 条用）。 */
fun Modifier.v4BottomDivider(color: Color): Modifier = drawBehind {
    val w = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}

/**
 * 顶部文字 tab（V4：13.5sp，选中 600 + 2dp 强调色下划线，整行底部 1px 分隔线）。
 * 横向可滚动；selected 为 -1 时都不选中。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun V4TopTabs(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((Int) -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val v = V4.c
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = V4.TabRow)
            .v4BottomDivider(v.div),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = V4.Gutter),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            titles.forEachIndexed { i, t ->
                val on = i == selected
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(
                            if (onLongClick != null) Modifier.combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(i) },
                                onLongClick = { onLongClick(i) },
                            ) else Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                            ) { onSelect(i) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t,
                        style = if (on) V4Type.TabOn else V4Type.Tab,
                        color = if (on) v.t1 else v.t2,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (on) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(v.accent),
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
 * 分段控件（V4：s1 轨道 8dp 圆角 2dp 内边距；选中 s3 6dp 圆角 t1 600）。
 * 用于 线路 源1–6、主题模式 等。
 */
@Composable
fun V4Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
) {
    val v = V4.c
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(V4.RField))
            .background(v.s1)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(
                if (on) v.s3 else Color.Transparent,
                tween(SoftMotion.Fast, easing = SoftMotion.Ease),
                label = "segBg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height - 4.dp)
                    .clip(RoundedCornerShape(V4.RPoster))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) v.t1 else v.t2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

enum class V4TagKind { Vip, Live, Accent, Neutral }

/** 小标签（V4：等宽 9sp、3dp 圆角、着色底）。 */
@Composable
fun V4Tag(text: String, kind: V4TagKind = V4TagKind.Vip, modifier: Modifier = Modifier) {
    val v = V4.c
    val (fg, bg) = when (kind) {
        V4TagKind.Vip -> v.vip to v.vipBg
        V4TagKind.Live -> v.live to v.liveBg
        V4TagKind.Accent -> v.accent to v.accentA
        V4TagKind.Neutral -> v.t2 to v.s3
    }
    Box(
        modifier
            .clip(RoundedCornerShape(V4.RTag))
            .background(bg)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(text, style = V4Type.Tag, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 6dp 状态点。 */
@Composable
fun V4Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** 小开关（32×18；开 = 强调色 + 白拇指，关 = s3 + 灰拇指；220ms）。 */
@Composable
fun V4Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val v = V4.c
    val track by animateColorAsState(if (checked) v.accent else v.s3, tween(SoftMotion.Fast), label = "swTrack")
    val thumb by animateColorAsState(if (checked) Color.White else v.thumbOff, tween(SoftMotion.Fast), label = "swThumb")
    val x by animateDpAsState(if (checked) 16.dp else 2.dp, tween(SoftMotion.Fast, easing = SoftMotion.Ease), label = "swX")
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 40.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange,
            )
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(track),
        ) {
            Box(
                Modifier
                    .offset(x = x, y = 2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(thumb),
            )
        }
    }
}

/** 区块标题（V4：32dp 高，12sp 600 + 等宽计数 t3 + 右侧操作 t2）。 */
@Composable
fun V4SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    trailingChevron: Boolean = false,
    onAction: (() -> Unit)? = null,
) {
    val v = V4.c
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = V4.SectionHeader),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = V4Type.Section, color = v.t1, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count, style = V4Type.MonoMeta, color = v.t3, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (actionText != null || actionIcon != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(V4.RSm))
                    .then(if (onAction != null) Modifier.clickable(onClick = onAction) else Modifier)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (actionIcon != null) {
                    Icon(actionIcon, null, tint = v.t2, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                }
                if (actionText != null) {
                    Text(actionText, fontSize = 11.5.sp, color = v.t2, maxLines = 1)
                }
                if (trailingChevron) {
                    Icon(Icons.Filled.ChevronRight, null, tint = v.t3, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/**
 * 设置/列表行（V4：44dp，底部 1px 分隔线；图标 17 t2；标题 13/500 + 可选 11sp t3 副标题；
 * 右侧值 t2 + 箭头 t3，或自定义尾巴（开关）。
 */
@Composable
fun V4Row(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    divider: Boolean = true,
    height: Dp = V4.RowSetting,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val v = V4.c
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .then(if (divider) Modifier.v4BottomDivider(v.div) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = V4.Gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = v.t2, modifier = Modifier.size(17.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = V4Type.RowTitle, color = v.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 11.sp, color = v.t3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) Text(value, fontSize = 12.sp, color = v.t2, maxLines = 1)
        if (trailing != null) trailing()
        else if (showChevron) Icon(Icons.Filled.ChevronRight, null, tint = v.t3, modifier = Modifier.size(16.dp))
    }
}

/** 34dp 图标按钮（8dp 圆角，t2 图标）。 */
@Composable
fun V4IconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = V4.c.t2,
    filled: Boolean = false,
    size: Dp = 34.dp,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(V4.RField))
            .then(if (filled) Modifier.background(V4.c.s1) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * 假输入框（点了进搜索页）：34dp，s1 底，放大镜 + t3 占位字。
 */
@Composable
fun V4SearchField(
    placeholder: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val v = V4.c
    Row(
        modifier = modifier
            .heightIn(min = V4.Field)
            .clip(RoundedCornerShape(V4.RField))
            .background(v.s1)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = v.t3, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(placeholder, fontSize = 12.5.sp, color = v.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 占位图（柔和两段斜向渐变 + 一道 12% 白圆环 + 顶部微光）——没图/图挂时用，不用假海报。 */
@Composable
fun V4Art(
    seed: String,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(V4.RPoster),
    ring: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val v = V4.c
    Box(
        modifier = modifier
            .clip(shape)
            .background(v.artBrush(seed))
            .drawBehind {
                if (ring) {
                    val r = size.minDimension * 0.42f
                    val h = (seed.hashCode() ushr 3) % 3
                    val c = when (h) {
                        0 -> Offset(size.width * 0.38f, size.height * 0.30f)
                        1 -> Offset(size.width * 0.62f, size.height * 0.62f)
                        else -> Offset(size.width * 0.55f, size.height * 0.45f)
                    }
                    drawCircle(v.artRing, r, c, style = Stroke(1.dp.toPx()))
                }
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.04f),
                        0.4f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (v.isDark) 0.25f else 0.06f),
                    ),
                )
            },
        content = content,
    )
}

/**
 * 环形仪表（SETTINGS B：48dp、半径 20、描边 3，轨道 7% 白，进度强调色圆头，从 12 点起）。
 * progress 0..1；中间等宽数字。
 */
@Composable
fun V4RingGauge(
    value: String,
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 48.dp,
) {
    val v = V4.c
    val track = if (v.isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.07f)
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val sw = 3.dp.toPx()
            val r = 20.dp.toPx().coerceAtMost(size.minDimension / 2 - sw / 2)
            val tl = Offset(center.x - r, center.y - r)
            val sz = androidx.compose.ui.geometry.Size(r * 2, r * 2)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            val p = progress.coerceIn(0f, 1f)
            if (p > 0f) {
                drawArc(v.accent, -90f, 360f * p, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        Text(value, style = V4Type.Stat, color = v.t1, maxLines = 1)
    }
}

/** 设置页 bento 磁贴底（s1、10dp 圆角、10dp 内边距、无描边）。 */
@Composable
fun V4Tile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(V4.RTile))
            .background(V4.c.s1)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** 小号次级按钮行内用（h34 r8，s2 底）。 */
@Composable
fun V4SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
) {
    val v = V4.c
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(V4.RField))
            .background(if (primary) v.accent else v.s1)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (primary) v.onAccent else v.t2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) v.onAccent else v.t1,
            maxLines = 1,
        )
    }
}

/** 浮动底栏后面的内容淡出（100dp，bg/0 → bg/.9 @55% → bg；不接收点击）。 */
@Composable
fun V4DockFade(modifier: Modifier = Modifier) {
    val bg = V4.c.bg
    Box(
        modifier
            .fillMaxWidth()
            .height(V4.DockFade)
            .background(
                Brush.verticalGradient(
                    0f to bg.copy(alpha = 0f),
                    0.55f to bg.copy(alpha = 0.9f),
                    1f to bg,
                ),
            ),
    )
}

/** 让 Box 填满父级的小工具（避免各处重复写）。 */
fun Modifier.v4Fill(): Modifier = this.fillMaxSize()

/**
 * 出血：在有左右内边距的容器（如网格 contentPadding 12dp）里让某一项横向撑满屏宽，
 * 用于 tab 条/分隔线这类要通栏的元素。
 */
fun Modifier.v4Bleed(horizontal: Dp): Modifier = this.then(
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
