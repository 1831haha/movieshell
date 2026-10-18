package com.movieshell.ui.bookshelf

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import com.movieshell.ui.components.AppCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.model.Novel
import com.movieshell.data.model.ReadingProgress
import com.movieshell.data.novel.TxtImporter
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.ReadingProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Storage

/**
 * v0.35.0 小说书架。
 * v0.36.0：长按删除（确认弹窗）；卡片显示上次阅读时间。
 * v0.66.0：去掉在追/想看/已看完分组，单列表按最后阅读时间倒序（用户要求：只留一个书架）。
 * v0.79.0：子结构重设计——顶部子 tab（书架/找书/书源），找书/书源直接嵌在本页，
 *   不再三级跳转（书架→找书页→书源管理页）。
 *
 * - 右上角 + 导入 TXT；点击书进阅读器。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
// v0.82.0：顶部 书架/找书/书源 tab 已移除（用户嫌丑）；找书、书源改为独立路由，
/// 书架页头部直接放入口按钮
fun BookshelfScreen(
    onNovelClick: (String) -> Unit = {},
    onFindBookClick: () -> Unit = {},
    onManageSourcesClick: () -> Unit = {},
) {
    BookshelfList(
        onNovelClick = onNovelClick,
        onFindBookClick = onFindBookClick,
        onManageSourcesClick = onManageSourcesClick,
    )
}

/** 书架列表（原子内容，从原 BookshelfScreen 拆出） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfList(
    onNovelClick: (String) -> Unit = {},
    onFindBookClick: () -> Unit = {},
    onManageSourcesClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var books by remember { mutableStateOf<List<Novel>>(emptyList()) }
    var progressMap by remember { mutableStateOf<Map<String, ReadingProgress>>(emptyMap()) }
    var importing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Novel?>(null) }

    fun reload() {
        scope.launch {
            val all = withContext(Dispatchers.IO) { NovelStore.list(context) }
            // v0.66.0：单列表，按最后阅读时间倒序
            val pm = withContext(Dispatchers.IO) {
                all.associate { novel ->
                    novel.id to (ReadingProgressStore.load(context, novel.id)
                        ?: ReadingProgress(novelId = novel.id))
                }
            }
            progressMap = pm
            books = all.sortedByDescending { pm[it.id]?.updatedAt ?: 0L }
        }
    }
    LaunchedEffect(Unit) { reload() }

    val pickTxt = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    } ?: "导入小说.txt"
                    val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.txt")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: return@runCatching false
                    // 用真实文件名当书名
                    val named = File(tmp.parent, name)
                    tmp.renameTo(named)
                    val imported = TxtImporter.import(named) ?: return@runCatching false
                    named.delete()
                    NovelStore.addNovel(context, imported.novel, imported.chapters)
                }.getOrDefault(false)
            }
            importing = false
            Toast.makeText(
                context,
                if (ok) "已加入书架" else "导入失败：不是有效的 TXT",
                Toast.LENGTH_SHORT,
            ).show()
            if (ok) reload()
        }
    }

    val gold = Color(0xFFD9C39A)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("书架", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = gold)
                Text(
                    // v0.66.0：单列表，显示总数
                    if (books.isEmpty()) "管理你的藏书" else "共 ${books.size} 本",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            var showAddMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showAddMenu = true }, enabled = !importing) {
                    Icon(Icons.Filled.Add, "添加书籍", tint = gold)
                }
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("添加本地书籍") },
                        leadingIcon = {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        },
                        onClick = {
                            showAddMenu = false
                            pickTxt.launch("text/plain")
                        },
                    )
                }
            }
            IconButton(onClick = onFindBookClick) {
                Icon(Icons.Filled.Search, "找书", tint = gold)
            }
            IconButton(onClick = onManageSourcesClick) {
                Icon(Icons.Filled.Storage, "书源管理", tint = gold)
            }
        }

        Spacer(Modifier.height(12.dp))

        // v0.66.0：分组 tab 已移除（用户要求只留一个书架）

        if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "书架空空，去导入一本小说吧",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(books, key = { it.id }) { novel ->
                    NovelCard(
                        novel = novel,
                        lastReadAt = progressMap[novel.id]?.updatedAt ?: 0L,
                        onClick = { onNovelClick(novel.id) },
                        onLongClick = { deleteTarget = novel },
                    )
                }
            }
        }
    }

    // v0.36.0：长按删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除《${target.title}》？") },
            text = { Text("将从书架移除，阅读进度也会清除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                NovelStore.removeNovel(context, target.id)
                                ReadingProgressStore.clear(context, target.id)
                            }
                            reload()
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** v0.36.0：上次阅读时间格式化。 */
private fun formatLastRead(ts: Long): String {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelCard(
    novel: Novel,
    lastReadAt: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val total = novel.totalChapters.coerceAtLeast(1)
    val read = novel.lastReadChapter.coerceIn(0, total - 1)
    val progress = if (novel.lastReadChapter == 0) 0f else (read + 1).toFloat() / total
    val percent = (progress * 100).toInt()
    val cyan = Color(0xFF4DD0E1) // v0.39.0：mockup 青色进度

    // v0.81.0：阅读 3.0 风格——封面 + 书名/作者/已读章节/共几章
    AppCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 76.dp)
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
                Text(
                    novel.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFD9C39A), // v0.39.0：香槟金标题
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                NovelMetaRow(
                    icon = Icons.Filled.Person,
                    text = novel.author?.takeIf { it.isNotBlank() } ?: "未知作者",
                )
                Spacer(Modifier.height(2.dp))
                NovelMetaRow(
                    icon = Icons.Filled.MenuBook,
                    text = if (novel.lastReadChapter == 0) "还没读过"
                    else "已读：第 ${read + 1} 章",
                )
                Spacer(Modifier.height(2.dp))
                NovelMetaRow(
                    icon = Icons.Filled.LibraryBooks,
                    text = "共 $total 章 · ${formatLastRead(lastReadAt)}",
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp),
                        color = cyan,
                        trackColor = cyan.copy(alpha = 0.2f),
                    )
                    Text(
                        "$percent%",
                        fontSize = 11.sp,
                        color = cyan,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** v0.81.0：书架列表项元信息行（图标 + 文字）。 */
@Composable
private fun NovelMetaRow(icon: ImageVector, text: String) {
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
