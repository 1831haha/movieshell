package com.movieshell.ui.bookshelf

import com.movieshell.ui.theme.V5

import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.movieshell.data.model.Novel
import com.movieshell.data.repo.NovelStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.theme.LocalBrandGradient

/**
 * v0.98.0：书架卡片组件。
 * - bookPressable：按压缩放（0.96）微交互，复用音乐四榜卡片的做法。
 * - NovelListCard / NovelGridCard：双视图卡片。
 * - BookshelfEmptyState：趣味空状态。
 * v0.178.0：阅读数据条已删，不再留死组件。
 */

/** 按压缩放。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bookPressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        label = "bookPress",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}

/** 书籍阅读进度（0..1）。
 * v0.217.0（F46）：按“是否动过书”算——正在读第 1 章时进度是 1/total，
 * 不再因下标 0 被算成没读过。 */
fun Novel.readFraction(): Float {
    val total = totalChapters.coerceAtLeast(1)
    val read = lastReadChapter.coerceIn(0, total - 1)
    return if (!hasStartedReading) 0f else (read + 1).toFloat() / total
}

/** v0.197.0：阅读式列表行——不用玻璃卡片，plain 行 + 底部分隔线；四行信息
 * （书名+未读数角标 / 作者 / 已读章节 / 最新章节或上次阅读），进度条去掉，
 * 与阅读书架行结构对齐。网格卡片不动（书架布局切换时仍用）。
 * v0.248.0：按参考图改行式——封面放大到 68x92、书名放宽到两行、行尾加总章数
 * 浅蓝胶囊，总章数只由胶囊承担、不再在正文重复；数据口径与回调一律不动，
 * 网格卡片仍不动。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovelListCard(
    novel: Novel,
    lastReadAt: Long,
    updateCount: Int = 0,
    // v0.211.0：下拉刷新逐本状态——checking 转圈、checkFailed 红色“失败”，
    // 转圈优先于失败（正在查的书不先贴失败）。
    checking: Boolean = false,
    checkFailed: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = novel.totalChapters.coerceAtLeast(1)
    val read = novel.lastReadChapter.coerceIn(0, total - 1)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .bookPressable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            Box(
                modifier = Modifier
                    // v0.294.6：封面改 2:3（64x96）+ 10dp 圆角，底色走 surfaceContainerHigh
                    .size(width = 64.dp, height = 96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (!novel.cover.isNullOrBlank()) {
                    AsyncImage(
                        model = novel.cover,
                        contentDescription = novel.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = novel.title.firstOrNull()?.toString() ?: "书",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        novel.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        // v0.109.0 A9：书名在玻璃卡片上，亮色主题白底青字难读，用明度自适应青色
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 阅读式未读角标：只显示数字（蓝底小 pill），不再是“+N 章”
                    if (updateCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        // v0.215.0：角标样式统一——数字 pill，不再呼吸脉冲（多本更新时满屏闪）。
                        Text(
                            text = "$updateCount",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                NovelMetaRow(
                    icon = Icons.Filled.Person,
                    text = novel.author?.takeIf { it.isNotBlank() } ?: "未知作者",
                )
                Spacer(Modifier.height(2.dp))
                NovelMetaRow(
                    icon = Icons.Filled.MenuBook,
                    // v0.214.0：书架行补在读章节标题（有标题才拼，无标题回落只显章号）。
                    text = if (!novel.hasStartedReading) "还没读过"
                    else "已读：第 ${read + 1} 章" + (novel.lastReadChapterTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                )
                Spacer(Modifier.height(2.dp))
                NovelMetaRow(
                    icon = Icons.Filled.LibraryBooks,
                    // v0.248.0：总章数改由行尾胶囊承担，本行只留最新章节或上次阅读，
                    // 不再重复写共 N 章（GPT 第 1 批复核：去重）。
                    // v0.249.0：目录未到时不显示假的最新章节——LOADING/FAILED 各有状态文案。
                    text = when (novel.dirState) {
                        NovelStore.DIR_LOADING -> "正在获取目录…"
                        NovelStore.DIR_FAILED -> "目录获取失败，点击重试"
                        else -> novel.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { "最新：$it" }
                            ?: formatLastReadTs(lastReadAt)
                    },
                )
            }
            // v0.248.0：行尾总章数胶囊（参考图右侧“80话”位）——浅蓝底自适应青字，
            // 深色主题下用低透明度蓝底，不照搬亮底；只展示，不可点。
            // v0.249.0：目录未到时胶囊不显示假的章数——LOADING 显示状态，FAILED 红底。
            Spacer(Modifier.width(8.dp))
            val isDirFailed = novel.dirState == NovelStore.DIR_FAILED
            Text(
                text = when (novel.dirState) {
                    NovelStore.DIR_LOADING -> "目录加载中"
                    NovelStore.DIR_FAILED -> "目录获取失败"
                    else -> "${total}章"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDirFailed) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(
                        if (isDirFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            // v0.211.0：行尾逐本状态——转圈 = 这本还在查，停了就是查完了；
            // 失败贴红色“失败”，有更新的数字角标本来就在书名旁。
            if (checking) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            } else if (checkFailed) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "失败",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    }
}

/** v0.98.0：网格卡片（封面墙）。
 * v0.294.6：Material You 紧凑重做——封面 2:3、12dp 圆角；书名最多两行（固定两行高，
 * 网格对齐）；副标题「读到第 x 章」/作者/目录状态；角标：更新数（primary）、失败（error）、
 * 未读（tertiaryContainer）；进度改封面底部细进度条，去掉黑色圆底进度环与黑圆多选底。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovelGridCard(
    novel: Novel,
    updateCount: Int = 0,
    checking: Boolean = false,
    checkFailed: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val progress = novel.readFraction()
    val total = novel.totalChapters.coerceAtLeast(1)
    val read = novel.lastReadChapter.coerceIn(0, total - 1)
    val coverShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier.bookPressable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(coverShape)
                .background(cs.surfaceContainerHigh)
                .then(
                    if (selectionMode && selected) Modifier.border(2.dp, cs.primary, coverShape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!novel.cover.isNullOrBlank()) {
                AsyncImage(
                    model = novel.cover,
                    contentDescription = novel.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = novel.title.firstOrNull()?.toString() ?: "书",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            // 进度：封面底部 3dp 细条（读过才显示）
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                            .background(cs.primary),
                    )
                }
            }
            // 左上角标：失败 > 更新数 > 未读
            val badge: Triple<String, Color, Color>? = when {
                checkFailed && !checking -> Triple("失败", cs.error, cs.onError)
                updateCount > 0 -> Triple("$updateCount", cs.primary, cs.onPrimary)
                !novel.hasStartedReading -> Triple("未读", cs.tertiaryContainer, cs.onTertiaryContainer)
                else -> null
            }
            badge?.let { (t, bg, fg) ->
                Text(
                    text = t,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = fg,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(bg, RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
            // 正在检查更新：整张封面浅色蒙层 + 小转圈（不再是黑圆底）
            if (checking) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(cs.surface.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = cs.primary,
                        strokeWidth = 2.dp,
                    )
                }
            }
            // 多选勾（右上）：选中=primary 实心勾，未选=浅色描边圆
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (selected) cs.primary else cs.surface.copy(alpha = 0.85f))
                        .then(
                            if (selected) Modifier
                            else Modifier.border(1.5.dp, cs.outline, CircleShape),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = cs.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            novel.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = cs.onSurface,
            maxLines = 2,
            minLines = 2,
            lineHeight = 17.sp,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        val (sub, subColor) = when (novel.dirState) {
            NovelStore.DIR_LOADING -> "正在获取目录…" to cs.onSurfaceVariant
            NovelStore.DIR_FAILED -> "目录获取失败，点击重试" to cs.error
            else -> (
                if (novel.hasStartedReading) "读到第 ${read + 1} 章"
                else novel.author?.takeIf { it.isNotBlank() } ?: "共 ${total} 章"
                ) to cs.onSurfaceVariant
        }
        Text(
            sub,
            style = MaterialTheme.typography.labelSmall,
            color = subColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** v0.98.0：趣味空状态。
 * v0.294.6：紧凑化——不再顶 120dp 空白，图标井缩小，一句短提示 + 找书/导入两个按钮。 */
@Composable
fun BookshelfEmptyState(
    onFindBook: () -> Unit,
    onImportTxt: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(V5.CornerLg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "书架还是空的",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "搜本小说，或导入本地 TXT",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                text = "去找书",
                onClick = onFindBook,
                icon = Icons.Filled.Search,
            )
            AppButton(
                text = "导入 TXT",
                onClick = onImportTxt,
                variant = AppButtonVariant.Secondary,
                icon = Icons.Outlined.UploadFile,
            )
        }
    }
}

/** 上次阅读时间格式化（从原 BookshelfScreen 搬运）。 */
fun formatLastReadTs(ts: Long): String {
    if (ts <= 0) return "还没读过"
    val now = System.currentTimeMillis()
    val diffMin = (now - ts) / 60000
    return when {
        diffMin < 1 -> "刚刚读过"
        diffMin < 60 -> "$diffMin 分钟前"
        diffMin < 60 * 24 -> "${diffMin / 60} 小时前"
        diffMin < 60 * 48 -> "昨天"
        else -> {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            "${c.get(java.util.Calendar.MONTH) + 1}-${c.get(java.util.Calendar.DAY_OF_MONTH)}"
        }
    }
}

/** 书架列表项元信息行（从原 BookshelfScreen 搬运）。 */
@Composable
fun NovelMetaRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
