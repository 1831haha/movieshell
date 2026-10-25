package com.movieshell.ui.bookshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.goldTitleColor
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

/** 书籍阅读进度（0..1）。 */
fun Novel.readFraction(): Float {
    val total = totalChapters.coerceAtLeast(1)
    val read = lastReadChapter.coerceIn(0, total - 1)
    return if (lastReadChapter == 0) 0f else (read + 1).toFloat() / total
}

/** v0.197.0：阅读式列表行——不用玻璃卡片，plain 行 + 底部分隔线；四行信息
 * （书名+未读数角标 / 作者 / 已读章节 / 共 N 章·上次阅读），进度条去掉，
 * 与阅读书架行结构对齐。网格卡片不动（书架布局切换时仍用）。 */
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
                .padding(vertical = 10.dp),
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
                    .size(width = 46.dp, height = 62.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        novel.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        // v0.109.0 A9：书名在玻璃卡片上，亮色主题白底金字难读，用明度自适应金
                        color = goldTitleColor(),
                        maxLines = 1,
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
                    text = if (novel.lastReadChapter == 0) "还没读过"
                    else "已读：第 ${read + 1} 章" + (novel.lastReadChapterTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                )
                Spacer(Modifier.height(2.dp))
                NovelMetaRow(
                    icon = Icons.Filled.LibraryBooks,
                    // v0.214.0：书架行补最新章节标题；老数据无标题时回落原来的“共 N 章 · 上次阅读”。
                    text = novel.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { "共 $total 章 · 最新：$it" }
                        ?: "共 $total 章 · ${formatLastReadTs(lastReadAt)}",
                )
            }
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
                        .background(Color(0xFFE53935), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    }
}

/** v0.98.0：网格卡片（封面墙）。 */
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
    val progress = novel.readFraction()
    val cyan = Color(0xFF4DD0E1)
    Column(
        modifier = modifier.bookPressable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // 封面 3:4
            Box(modifier = Modifier.fillMaxWidth()) {
                if (!novel.cover.isNullOrBlank()) {
                    AsyncImage(
                        model = novel.cover,
                        contentDescription = novel.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = novel.title.firstOrNull()?.toString() ?: "书",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            // 进度环（右下）
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(30.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(22.dp),
                        color = cyan,
                        trackColor = Color.White.copy(alpha = 0.25f),
                        strokeWidth = 3.dp,
                    )
                }
            }
            // v0.211.0：逐本状态——查完失败贴左上红“失败”（有更新时角标在，
            // 失败不可能同时有更新，不冲突）；正在查则盖一层转圈在封面中央。
            if (checkFailed && !checking) {
                Text(
                    text = "失败",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xFFE53935), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (checking) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
            }
            // v0.215.0：网格角标与列表同款——数字 pill、主题 primary、无脉冲、无“+”。
            if (updateCount > 0 && !(checkFailed && !checking)) {
                Text(
                    text = "$updateCount",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            // 多选勾（右上）
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Black.copy(alpha = 0.5f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            // v0.105.0：选中底是主题 primary（含浅色），用明度自适应的 onPrimary
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            novel.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // v0.214.0：网格卡片也补最新章节标题（无标题时不占行）。
        novel.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { latest ->
            Text(
                "最新：$latest",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** v0.98.0：趣味空状态。 */
@Composable
fun BookshelfEmptyState(
    onFindBook: () -> Unit,
    onImportTxt: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(
                    Brush.linearGradient(LocalBrandGradient.current),
                    CircleShape,
                )
                .padding(1.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF171B28), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LibraryBooks,
                    contentDescription = null,
                    tint = Color(0xFFD9C39A),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "书架还在等它的第一本书",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "找本小说开始读，打卡天数从今天算起",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppButton(text = "去找书", onClick = onFindBook)
            AppButton(
                text = "导入 TXT",
                onClick = onImportTxt,
                variant = AppButtonVariant.Secondary,
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
