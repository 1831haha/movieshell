package com.movieshell.ui.bookshelf

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * - BookActionSheet：长按操作表（继续阅读/详情/移入分组/换源/删除）。
 * - BookDetailDialog：书籍详情（简介/目录/换源/净化规则/移入分组/删除）。
 * - GroupPickerDialog：选择分组（移入）。
 * - GroupNameDialog：新建/重命名分组。
 * v0.178.0：长按操作表新增全选/收藏；分组仍走移入分组。
 */

/** 长按操作表。 */
@Composable
fun BookActionSheet(
    novel: Novel,
    isFavorite: Boolean = false,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onDetail: () -> Unit,
    onMoveGroup: () -> Unit,
    onChangeSource: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
) {
    TechDialog(
        onDismissRequest = onDismiss,
        title = novel.title,
        onConfirm = null,
        dismissText = "关闭",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SheetRow(Icons.Filled.Checklist, "全选", onSelectAll)
            SheetRow(Icons.Filled.DriveFileMove, "分组", onMoveGroup)
            SheetRow(
                if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                if (isFavorite) "取消收藏" else "收藏",
                onToggleFavorite,
            )
            SheetRow(Icons.Filled.PlayArrow, "继续阅读", onRead)
            SheetRow(Icons.Filled.Info, "书籍详情", onDetail)
            SheetRow(Icons.Filled.SwapHoriz, "换书源", onChangeSource)
            SheetRow(
                Icons.Filled.Delete,
                "删除",
                onDelete,
                tint = Color(0xFFE53935),
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    // v0.194.0：弹窗体跟随主题（TechDialog），行色跟主题走，不再写死浅色。
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            // v0.193.0：行高收紧（12dp→8dp），操作表不再像个空壳大框。
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp, color = tint)
    }
}

/** 书籍详情弹窗。 */
@Composable
fun BookDetailDialog(
    novel: Novel,
    lastReadAt: Long,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onMoveGroup: () -> Unit,
    onDelete: () -> Unit,
    onOpenPurifyRules: () -> Unit,
    onSourceSwitched: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showToc by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
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
            ?: pyRules.firstOrNull { it.name == novel.sourceKey }?.title
            ?: novel.sourceKey
    }

    LaunchedEffect(showToc) {
        if (showToc && chapters == null) {
            chapters = withContext(Dispatchers.IO) {
                runCatching { NovelStore.chapters(context, novel.id) }.getOrNull()
            }
        }
    }

    TechDialog(
        onDismissRequest = onDismiss,
        title = novel.title,
        // v0.197.0：作者/章数不再放副标题，改放下面的标签与信息行（阅读书籍信息页结构）
        subtitle = null,
        onConfirm = null,
        dismissText = "关闭",
    ) {
        Column {
            // v0.197.0：阅读式书籍信息头——封面 + 蓝色标签（共 N 章 / 已读进度）+ 上次阅读
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 86.dp)
                        .clip(RoundedCornerShape(8.dp))
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
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoChip("共 $total 章")
                        InfoChip(if (novel.lastReadChapter == 0) "未读" else "已读 $percent%")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (novel.lastReadChapter == 0) "还没读过"
                        else "已读：第 ${read + 1} 章",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "上次阅读：${formatLastReadTs(lastReadAt)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(4.dp))
            if (!novel.desc.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    novel.desc,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            // v0.197.0：阅读式信息行（标签定宽 + 值 + 右侧动作）；字数/分类/状态/
            // 人气咱没有真实数据，不编假标签，只列有的项
            InfoRow(label = "作者", value = novel.author?.takeIf { it.isNotBlank() } ?: "未知作者")
            InfoRow(label = "来源", value = sourceName, actionText = "换源", onAction = { showChangeSource = true })
            InfoRow(label = "最新", value = "共 $total 章")
            InfoRow(label = "分组", actionText = "设置分组", onAction = onMoveGroup)
            InfoRow(
                label = "目录",
                actionText = if (showToc) "收起目录" else "查看目录",
                onAction = { showToc = !showToc },
            )
            InfoRow(label = "净化规则", actionText = "管理", onAction = onOpenPurifyRules)
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
                                    .clickable { onRead() }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = ch.title.ifBlank { "第 ${ch.index + 1} 章" },
                                    fontSize = 13.sp,
                                    color = if (ch.index <= read) Color(0xFF4DD0E1)
                                    // v0.193.0：未读章同 SheetRow，不用主题 onSurface。
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))
            // v0.197.0：阅读式底部栏——左侧删除书籍文字钮，右侧蓝色阅读钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDelete) {
                    Text("删除书籍", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                AppButton(
                    text = "阅读",
                    onClick = onRead,
                    modifier = Modifier.width(140.dp),
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
            onSwitched = {
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

/** v0.197.0：书籍信息行——标签定宽 + 值 + 右侧动作（无值时只留动作）。 */
@Composable
private fun InfoRow(
    label: String,
    value: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        if (value != null) {
            Text(
                value,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionText, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
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
                    // v0.193.0：同 SheetRow，深底弹窗内不用主题色。
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
                    tint = Color(0xFF4DD0E1),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text("新建分组", fontSize = 15.sp, color = Color(0xFF4DD0E1))
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
