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

/**
 * v0.27.0 Soft Tech 公共组件（单入口，各屏不许自建卡片/按钮）。
 */

/** 通用卡片：surface 底 + 1dp 低对比描边 + 20dp 圆角。 */
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
            .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, shape)
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
        color = LocalSoftExtra.current.glass,
        tonalElevation = 0.dp,
    ) {
        // v0.27.1：内容纵向排。Surface 默认是 Box，多 children 会叠在一起
        //（曾导致设置页"运行日志悬浮窗"行被下一行盖住、主题色行与色圈重叠）。
        Column { content() }
    }
}

enum class AppButtonVariant { Primary, Secondary, Ghost, Cyan }

/** 通用按钮：主/次/幽灵/青色四档，16dp 圆角，触摸目标 48dp。可选前置图标。 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val cyan = Color(0xFF4DD0E1) // v0.39.0：mockup 青色高光
    val colors = when (variant) {
        AppButtonVariant.Primary -> ButtonDefaults.buttonColors()
        AppButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
        AppButtonVariant.Ghost -> ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        )
        // v0.39.0：青色胶囊（mockup"随机开片"风格），深色字
        AppButtonVariant.Cyan -> ButtonDefaults.buttonColors(
            containerColor = cyan,
            contentColor = Color(0xFF0B1A20),
            disabledContainerColor = cyan.copy(alpha = 0.35f),
            disabledContentColor = Color(0xFF0B1A20).copy(alpha = 0.5f),
        )
    }
    val shape = RoundedCornerShape(
        if (variant == AppButtonVariant.Cyan) SoftDimens.ChipCorner else SoftDimens.ButtonCorner
    )
    val content: @Composable RowScope.() -> Unit = {
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
    small: Boolean = false, // v0.30.1：二级筛选行用小一号
) {
    // v0.237.0 浅色高密度：浅色下按参考图——选中浅蓝底＋参考蓝字、
    // 未选中浅灰底＋灰字，无描边无阴影；深色保持原来的微光描边。
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    // v0.33.0：选中态微光描边——主色 1dp 边 + 柔光阴影（深色）
    val glowColor = MaterialTheme.colorScheme.primary
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = SoftType.Caption) },
        modifier = modifier
            .height(if (small) if (isLight) 28.dp else 30.dp else if (isLight) 32.dp else 36.dp)
            .then(
                if (selected && !isLight) Modifier.shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(SoftDimens.ChipCorner),
                    ambientColor = glowColor.copy(alpha = 0.35f),
                    spotColor = glowColor.copy(alpha = 0.35f),
                ) else Modifier
            ),
        shape = RoundedCornerShape(SoftDimens.ChipCorner),
        colors = if (isLight) FilterChipDefaults.filterChipColors(
            containerColor = Color(0xFFF1F3F4),
            selectedContainerColor = Color(0xFFE3F0FD),
            labelColor = Color(0xFF5F6364),
            selectedLabelColor = Color(0xFF1E88E5),
        ) else FilterChipDefaults.filterChipColors(
            containerColor = LocalSoftExtra.current.glass,
            selectedContainerColor = glowColor.copy(alpha = 0.22f),
            // v0.107.0 P0-10：照 v0.105.0 口径（CapsuleTabBar 同款），浅色主题色下选中字取深色，
            // 之前直接用主色原色，亮色主题+浅色主题色时对比度约 1.2:1 不可读
            selectedLabelColor = if (glowColor.luminance() > 0.5f) Color(0xFF1A1408) else glowColor,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = if (isLight) Color.Transparent else Color.White.copy(alpha = 0.10f),
            selectedBorderColor = if (isLight) Color.Transparent else glowColor.copy(alpha = 0.9f),
            borderWidth = SoftDimens.BorderWidth,
        ),
    )
}

/**
 * v0.109.0 A9：强调标题色——暗底用青色 #4DD0E1（风格统一后取代香槟金）；
 * 亮色主题下页面/玻璃卡片为白色系，青字在其上需加深保证对比度，
 * 按底色明度自适应改用深青 #0E7490（对白约 5:1）。
 * 固定深色底（如 TechDialog 弹窗体、#171B28 图标圆）上的青字不受影响，继续用原色。
 * v0.237.0：浅色高密度改版后，浅色下分区标题按参考图用近黑，不再用深青。
 */
@Composable
fun accentTitleColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF202124)
    else Color(0xFF4DD0E1)

/** 分区标题：15sp Medium + 可选右侧操作。风格统一后为青色强调色。 */
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
        Text(title, style = SoftType.CardTitle, color = accentTitleColor())
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

/** 悬浮 dock 导航项。 */
data class SoftDockItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * v0.27.1 悬浮 dock 式底部导航（替代 Material3 NavigationBar 的脏灰药丸选中态）。
 * 左右 16dp、底部 12dp 边距，24dp 圆角，#1E242F 微抬升底 + 柔光阴影；
 * v0.32.0：选中色固定雾蓝 #7DD3FC，不跟随主题主色，避免冲突；
 * v0.36.0：选中色改青色 #4DD0E1（mockup 风格）；未选中用 Outlined 图标，选中用 Filled 图标；
 * 未选中 #9AA4B2。
 * 全量重做：dock 改版方案三（夜航极简，GPT 方案）——无选中底块，选中只靠青色
 * 图标＋文字＋14dp 短线；未选中 #8491A1；图标 22dp、文字 10sp；指示线常驻占位（未选中透明），切 tab 不跳布局。
 * v0.238.0：悬浮药丸改通栏贴底——高 60dp、直角、无阴影、无侧边与底部边距。
 */
@Composable
fun SoftBottomDock(
    items: List<SoftDockItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // v0.238.0：底栏贴底拉平——去掉悬浮边距与圆角，通栏贴底；
    // 高 60dp、直角、无阴影，只留顶部一道发丝线（v0.251.0：此前代码仍是四边描边，现改实）；
    // 选中色与图标字体不动。
    val shape = RoundedCornerShape(0.dp)
    // v0.237.0：浅色下底栏按参考图——白底、浅灰描边、参考蓝选中、灰字未选中；
    // 深色保持夜航极简原样（颜色）。
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val unselected = if (isLight) Color(0xFF5F6364) else Color(0xFF8491A1)
    val accent = if (isLight) Color(0xFF1E88E5) else Color(0xFF4DD0E1)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .drawBehind {
                // v0.251.0：四边描边改顶部一道发丝线，与页面过渡自然。
                val stroke = 1.dp.toPx()
                drawLine(
                    color = if (isLight) Color(0xFFE8EAED) else Color(0x1FFFFFFF),
                    start = Offset(0f, stroke / 2),
                    end = Offset(size.width, stroke / 2),
                    strokeWidth = stroke,
                )
            },
        shape = shape,
        color = if (isLight) Color.White else Color(0xF2121720),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                val selected = i == selectedIndex
                val fg = if (selected) accent else unselected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(i) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.icon,
                        contentDescription = item.label,
                        tint = fg,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = fg,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    // 指示线常驻占位：未选中时透明，切 tab 时布局不跳。
                    Box(
                        modifier = Modifier
                            .size(width = 14.dp, height = 2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (selected) accent else Color.Transparent),
                    )
                }
            }
        }
    }
}

