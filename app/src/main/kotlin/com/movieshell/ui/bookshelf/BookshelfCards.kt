package com.movieshell.ui.bookshelf

import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.filled.Search
import com.movieshell.ui.theme.V4
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
import com.movieshell.data.repo.NovelStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.accentTitleColor
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
                    .size(width = 68.dp, height = 92.dp)
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
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        novel.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        // v0.109.0 A9：书名在玻璃卡片上，亮色主题白底青字难读，用明度自适应青色
                        color = accentTitleColor(),
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
                color = if (isDirFailed) Color.White else accentTitleColor(),
                modifier = Modifier
                    .background(
                        if (isDirFailed) V4.c.live
                        else V4.c.accent.copy(alpha = 0.14f),
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
                        .background(V4.c.live, RoundedCornerShape(10.dp))
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
    val cyan = V4.c.accent
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
                        .background(V4.c.live, RoundedCornerShape(8.dp))
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
        // v0.249.0：目录未到时这行显示状态文案，不显示假数据。
        when (novel.dirState) {
            NovelStore.DIR_LOADING -> Text(
                "正在获取目录…",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            NovelStore.DIR_FAILED -> Text(
                "目录获取失败，点击重试",
                fontSize = 11.sp,
                color = V4.c.live,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> novel.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { latest ->
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
            .padding(top = 120.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // v0.279.0 V4：68dp s1 圆角图标井 + 15/600 标题 + t3 提示 + 主/次两个按钮
        Box(
            modifier = Modifier
                .size(68.dp)
                .background(V4.c.s1, RoundedCornerShape(V4.RWell)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Book,
                contentDescription = null,
                tint = V4.c.t2,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "书架还在等它的第一本书",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = V4.c.t1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "找本小说开始读，打卡天数从今天算起",
            fontSize = 12.sp,
            color = V4.c.t3,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppButton(
                text = "去找书",
                onClick = onFindBook,
                icon = Icons.Filled.Search,
                modifier = Modifier.width(146.dp),
            )
            AppButton(
                text = "导入 TXT",
                onClick = onImportTxt,
                variant = AppButtonVariant.Secondary,
                icon = Icons.Outlined.UploadFile,
                modifier = Modifier.width(146.dp),
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
