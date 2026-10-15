package com.movieshell.ui.bookshelf

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.movieshell.data.model.ShelfTab
import com.movieshell.data.novel.TxtImporter
import com.movieshell.data.repo.NovelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.35.0 小说书架。
 *
 * - 在追 / 想看 / 已看完 三个分组（收藏片单下版再说）；
 * - 右上角 + 导入 TXT；点击书进阅读器；长按…暂时只做删除按钮。
 */
@Composable
fun BookshelfScreen(
    onNovelClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(ShelfTab.READING) }
    var shelves by remember { mutableStateOf<Map<ShelfTab, List<Novel>>>(emptyMap()) }
    var importing by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            shelves = withContext(Dispatchers.IO) { NovelStore.listShelves(context) }
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
    val books = shelves[tab].orEmpty()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("书架", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = gold)
                Text(
                    "管理你的藏书",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { pickTxt.launch("text/plain") }, enabled = !importing) {
                Icon(Icons.Filled.Add, "导入 TXT", tint = gold)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShelfTabChip("在追", tab == ShelfTab.READING, shelves[ShelfTab.READING].orEmpty().size) {
                tab = ShelfTab.READING
            }
            ShelfTabChip("想看", tab == ShelfTab.WANT, shelves[ShelfTab.WANT].orEmpty().size) {
                tab = ShelfTab.WANT
            }
            ShelfTabChip("已看完", tab == ShelfTab.FINISHED, shelves[ShelfTab.FINISHED].orEmpty().size) {
                tab = ShelfTab.FINISHED
            }
        }

        Spacer(Modifier.height(12.dp))

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
                        onClick = { onNovelClick(novel.id) },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { NovelStore.removeNovel(context, novel.id) }
                                reload()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfTabChip(label: String, selected: Boolean, count: Int, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$label $count") },
    )
}

@Composable
private fun NovelCard(novel: Novel, onClick: () -> Unit, onDelete: () -> Unit) {
    val total = novel.totalChapters.coerceAtLeast(1)
    val read = novel.lastReadChapter.coerceIn(0, total - 1)
    val progress = if (novel.lastReadChapter == 0) 0f else (read + 1).toFloat() / total
    val percent = (progress * 100).toInt()

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        novel.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (!novel.author.isNullOrBlank()) {
                        Text(
                            novel.author!!,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "读到第 ${read + 1} 章 / 共 $total 章",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(6.dp),
                )
                Text(
                    "$percent%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
