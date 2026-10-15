package com.movieshell.ui.novel

import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.novel.DemoNovelSource
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.repo.NovelStore
import kotlinx.coroutines.launch

/**
 * v0.37.0 找书页：演示源搜索 → 加入书架（自动拉目录）。
 */
@Composable
fun NovelSearchScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gold = Color(0xFFD9C39A)

    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<NovelSearchResult>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        scope.launch {
            loading = true
            searched = true
            results = runCatching { DemoNovelSource.search(keyword) }.getOrDefault(emptyList())
            loading = false
        }
    }

    fun joinShelf(item: NovelSearchResult) {
        scope.launch {
            joining = item.remoteId
            val ok = runCatching {
                val metas = DemoNovelSource.chapters(item)
                val novel = Novel(
                    id = "demo_${item.remoteId}",
                    sourceKey = DemoNovelSource.key,
                    title = item.title,
                    author = item.author,
                    addedAt = System.currentTimeMillis(),
                )
                val chapters = metas.map {
                    Chapter(
                        novelId = novel.id,
                        index = it.index,
                        title = it.title,
                        content = DemoNovelSource.content(it),
                    )
                }
                NovelStore.addNovel(context, novel, chapters)
            }.getOrDefault(false)
            joining = null
            Toast.makeText(context, if (ok) "已加入书架" else "加入失败", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = gold)
            }
            Text("找书", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = gold)
            Spacer(Modifier.weight(1f))
            Text(DemoNovelSource.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("书名 / 作者") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { doSearch() }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Filled.Search, "搜索", tint = gold)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            searched && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没找到，换个关键词试试", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(results, key = { it.remoteId }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(
                                    item.author ?: "佚名",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val busy = joining == item.remoteId
                            Button(onClick = { joinShelf(item) }, enabled = !busy) {
                                Text(if (busy) "加入中" else "加入书架")
                            }
                        }
                    }
                }
            }
        }
    }
}
