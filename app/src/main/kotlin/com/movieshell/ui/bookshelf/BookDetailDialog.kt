package com.movieshell.ui.bookshelf

import com.movieshell.ui.theme.V4
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.repo.BookGroup
import com.movieshell.data.repo.NovelStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.reader.ChangeSourceDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.98.0：书架弹窗组。
 * - BookDetailDialog：书籍详情（简介/目录/换源/净化规则/移入分组/删除）。
 * - GroupPickerDialog：选择分组（移入）。
 * - GroupNameDialog：新建/重命名分组。
 * v0.211.0：长按操作表已删除——书架长按直接进书籍详情（用户截图口径），
 * 操作表里的分组/换源/删除/收藏全部收进详情页；全选改走顶部菜单“书架管理”。
 */

/** 书籍详情弹窗。 */
@Composable
fun BookDetailDialog(
    novel: Novel,
    lastReadAt: Long,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    // v0.211.0 审计修复：目录行点某章要直达该章（原来只调无参 onRead，
    // 点哪章都按旧进度打开，目录跳转语义不存在）。
    onReadChapter: ((Int) -> Unit)? = null,
    onMoveGroup: () -> Unit,
    onDelete: () -> Unit,
    onOpenPurifyRules: () -> Unit,
    onSourceSwitched: () -> Unit,
    // v0.211.0：长按直达详情后，详情页自己要显示分组名和收藏态（调用方给真实值）。
    groupName: String = "未分组",
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showToc by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var chaptersFor by remember { mutableStateOf("") }
    var showChangeSource by remember { mutableStateOf(false) }
    val total = novel.totalChapters.coerceAtLeast(1)
    val read = novel.lastReadChapter.coerceIn(0, total - 1)
    val percent = (novel.readFraction() * 100).toInt()
    // v0.197.0：来源显示名（JSON 查名、PY 查标题、本地直显；查不到回落 key）
    val jsonSources by BookSourceManager.sources.collectAsState()
    val pyRules by PyBookSourceManager.rules.collectAsState()
    val sourceName = when (novel.sourceKey) {
        "local" -> "本地"
        else -> jsonSources.firstOrNull { it.key == novel.sourceKey }?.name
            // v0.211.0 审计修复：PY 书 sourceKey 是 "pybook:<文件名>"，
            // 原来拿裸文件名跟它比永远对不上，详情来源行只能回落显示
            // 裸 key；先剥掉前缀再比文件名。
            ?: pyRules.firstOrNull { it.name == novel.sourceKey.removePrefix(PyBookSourceManager.SOURCE_KEY_PREFIX) }?.title
            ?: novel.sourceKey
    }

    // v0.211.0：进详情就拉本地目录——“最新”要显示最后一章的真实标题，
    // 目录展开时也不用再等一次。章节是入架时已存本地的，不是现抓。
    // v0.211.0 审计修复：原来只以 novel.id 为 key 且 chapters 非空
    // 就不重拉——详情内换源后目录和“最新”仍是旧源的。key 加上
    // sourceKey，换源后强制重拉一次。
    // v0.217.0（F37）：守卫条件只比 sourceKey，同源换书时条件为假、直接
    // 跳过重拉，第二本详情里还是第一本的目录和“最新”；改记“书 id＋源 key”，
    // 换书、换源都必重拉。
    LaunchedEffect(novel.id, novel.sourceKey) {
        val key = novel.id + "|" + novel.sourceKey
        if (chapters == null || chaptersFor != key) {
            chapters = withContext(Dispatchers.IO) {
                runCatching { NovelStore.chapters(context, novel.id) }.getOrNull()
            }
            chaptersFor = key
        }
    }

    TechDialog(
        onDismissRequest = onDismiss,
        title = novel.title,
        // v0.197.0：作者/章数不再放副标题，改放下面的标签与信息行（阅读书籍信息页结构）
        subtitle = null,
        onConfirm = null,
        dismissText = "关闭",
        // v0.240.0：弹窗真正改小——宽度收进屏幕 80%，239 那版只收了封面和间距，
        // 整体还是接近满屏，看着像没改。
        widthFraction = 0.80f,
    ) {
        // v0.211.0：详情内容可上下滚——大封面 + 7 行信息 + 简介 + 目录展开后
        // 在小屏上会超一屏，不滚就被裁掉（原弹窗就有这个隐患，目录展开时最明显）。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            // v0.211.0：截图式信息头——大封面居中 + 蓝色标签居中；
            // 弹窗标题已是书名，头里不再重复写一遍。
            // v0.239.0：弹窗瘦身——封面 110x148 改 84x112，间距同步收紧。
            // v0.240.0：再收一圈——封面改 72x96，行距与间距同步收紧。
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!novel.cover.isNullOrBlank()) {
                    AsyncImage(
                        model = novel.cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        novel.title.firstOrNull()?.toString() ?: "书",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // v0.249.0：目录未到时不显示假的章数。
                InfoChip(
                    when (novel.dirState) {
                        NovelStore.DIR_LOADING -> "目录加载中"
                        NovelStore.DIR_FAILED -> "目录获取失败"
                        else -> "共 $total 章"
                    },
                )
                InfoChip(if (!novel.hasStartedReading) "未读" else "已读 $percent%")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (!novel.hasStartedReading) "还没读过"
                else "已读：第 ${read + 1} 章" + (novel.lastReadChapterTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + " · 上次阅读：${formatLastReadTs(lastReadAt)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(6.dp))
            // v0.211.0：截图式信息行——左侧图标 + 标签 + 值，右侧蓝色小钮；
            // 字数/分类/状态/人气咱没有真实数据，不编假标签，只列有的项。
            // “最新”用本地目录最后一章的真实标题，目录还没拉到时回落“共 N 章”。
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(icon = Icons.Filled.Person, label = "作者", value = novel.author?.takeIf { it.isNotBlank() } ?: "未知作者")
                InfoRow(icon = Icons.Filled.Public, label = "来源", value = sourceName, actionText = "换源", onAction = { showChangeSource = true })
                // v0.249.0：目录未到时“最新”不回落假的章数，显示状态。
                InfoRow(
                    icon = Icons.Filled.Explore,
                    label = "最新",
                    value = when (novel.dirState) {
                        NovelStore.DIR_LOADING -> "正在获取目录…"
                        NovelStore.DIR_FAILED -> "目录获取失败"
                        else -> chapters?.lastOrNull()?.title?.takeIf { it.isNotBlank() } ?: "共 $total 章"
                    },
                )
                InfoRow(icon = Icons.Filled.Tune, label = "分组", value = groupName, actionText = "设置分组", onAction = onMoveGroup)
                // v0.214.0：单本追更开关——关掉的书手动/后台检查都跳过（详情页拨动即写盘）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "追更",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp),
                    )
                    var updateOn by remember(novel.id) { mutableStateOf(novel.updateEnabled) }
                    Text(
                        if (updateOn) "开" else "关",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = updateOn,
                        onCheckedChange = { on ->
                            updateOn = on
                            scope.launch { NovelStore.setUpdateEnabled(context, novel.id, on) }
                        },
                    )
                }
                InfoRow(
                    icon = Icons.Filled.Folder,
                    label = "目录",
                    actionText = if (showToc) "收起目录" else "查看目录",
                    onAction = { showToc = !showToc },
                )
                if (onToggleFavorite != null) {
                    InfoRow(
                        icon = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        label = "收藏",
                        value = if (isFavorite) "已收藏" else "未收藏",
                        actionText = if (isFavorite) "取消收藏" else "收藏",
                        onAction = onToggleFavorite,
                    )
                }
                InfoRow(icon = Icons.Filled.FilterAlt, label = "净化规则", actionText = "管理", onAction = onOpenPurifyRules)
            }
            Spacer(Modifier.height(6.dp))
            if (!novel.desc.isNullOrBlank()) {
                Text(
                    novel.desc,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (showToc) {
                Spacer(Modifier.height(8.dp))
                val list = chapters
                if (list == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(list, key = { it.index }) { ch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onReadChapter?.invoke(ch.index) ?: onRead() }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = ch.title.ifBlank { "第 ${ch.index + 1} 章" },
                                    fontSize = 13.sp,
                                    color = if (ch.index <= read) V4.c.accent
                                    // v0.193.0：未读章同详情行，不用主题 onSurface。
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(6.dp))
            // v0.211.0：截图式底部双钮——左灰删除、右蓝阅读，各占一半宽。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton(
                    text = "删除书籍",
                    onClick = onDelete,
                    variant = AppButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "阅读",
                    onClick = onRead,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showChangeSource) {
        val chs = chapters
        ChangeSourceDialog(
            novel = novel,
            currentChapterTitle = chs?.getOrNull(read)?.title ?: "",
            currentChapterIndex = read,
            onDismiss = { showChangeSource = false },
            onSwitched = { newIndex ->
                // v0.211.0 审计修复：详情内换源算出的对齐下标原来被
                // 丢弃（onSourceSwitched 是无参的），下次进阅读器仍按
                // 旧下标恢复；这里先把对齐结果写进进度，再通知父级。
                // v0.217.0（F44）：标题按新目录算——原来不写标题，书架行
                // 仍挂旧源章名；新目录刚由换源写盘，从盘上取最准。
                scope.launch {
                    val newTitle = runCatching { NovelStore.chapters(context, novel.id) }
                        .getOrNull()?.getOrNull(newIndex)?.title.orEmpty()
                    com.movieshell.data.repo.ReadingProgressStore.save(
                        context,
                        com.movieshell.data.model.ReadingProgress(novel.id, newIndex, 0, chapterTitle = newTitle),
                    )
                }
                showChangeSource = false
                onSourceSwitched()
            },
        )
    }
}

/** v0.197.0：书籍信息页蓝色小标签（阅读式）。 */
@Composable
private fun InfoChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

/** v0.197.0：书籍信息行——标签定宽 + 值 + 右侧动作（无值时只留动作）。
 * v0.211.0：截图式——左侧图标 + 蓝色小钮（原 TextButton 文字链太素）。 */
@Composable
private fun InfoRow(
    label: String,
    value: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        if (value != null) {
            Text(
                value,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(actionText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
            }
        }
    }
}

/** 分组选择弹窗（移入）。 */
@Composable
fun GroupPickerDialog(
    groups: List<BookGroup>,
    onDismiss: () -> Unit,
    onPick: (BookGroup) -> Unit,
    onCreateNew: () -> Unit,
) {
    TechDialog(
        onDismissRequest = onDismiss,
        title = "移入分组",
        onConfirm = null,
        dismissText = "取消",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (groups.isEmpty()) {
                Text(
                    "还没有自定义分组，先建一个吧",
                    fontSize = 13.sp,
                    // v0.193.0：同详情行，深底弹窗内不用主题色。
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            groups.forEach { g ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(g) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        g.name,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${g.novelIds.size} 本",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onCreateNew)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = V4.c.accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text("新建分组", fontSize = 15.sp, color = V4.c.accent)
            }
        }
    }
}

/** 新建/重命名分组。 */
@Composable
fun GroupNameDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    TechDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmText = "确定",
        onConfirm = { onConfirm(name) },
        confirmEnabled = name.isNotBlank(),
    ) {
        TechTextField(
            value = name,
            onValueChange = { if (it.length <= 12) name = it },
            placeholder = "分组名（最多12字）",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
