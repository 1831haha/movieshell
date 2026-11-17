package com.movieshell.ui.bookshelf

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.novel.NovelDirectoryFill
import com.movieshell.data.store.NovelStore
import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.Novel
import com.movieshell.ui.market.PluginUi
import com.movieshell.ui.reader.ReaderJumpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.2.25：书籍目录整页——书架详情弹窗点“目录”进来（用户 2026-11-17 要求：
 * 目录单开一页，不再是弹窗里收起展开）。
 * 章节读本地已存目录（与详情弹窗同口径）；目录为空时给失败态＋重新获取
 * （走书架同款 NovelDirectoryFill.refillFromShelf，终态由 completed 流回推重读）。
 */
@Composable
fun NovelTocScreen(
    novelId: String,
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var novel by remember { mutableStateOf<Novel?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var reversed by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            val n = withContext(Dispatchers.IO) { NovelStore.get(context, novelId) }
            val cs = withContext(Dispatchers.IO) { NovelStore.chapters(context, novelId) }
            novel = n
            chapters = cs
            retrying = false
        }
    }
    LaunchedEffect(novelId) { load() }
    // 补取终态（READY/FAILED）后重读目录。
    LaunchedEffect(novelId) {
        NovelDirectoryFill.completed.collect { doneId ->
            if (doneId == novelId) load()
        }
    }

    val n = novel
    val list = chapters
    val read = n?.lastReadChapter?.coerceAtLeast(0) ?: 0
    val display = remember(list, reversed) {
        if (list == null) null else if (reversed) list.asReversed() else list
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    n?.title ?: "目录",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        list == null -> "加载中…"
                        list.isEmpty() -> "目录获取失败"
                        else -> "共 ${list.size} 章 · 已读到第 ${read + 1} 章"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!list.isNullOrEmpty()) {
                IconButton(onClick = { reversed = !reversed }) {
                    Icon(
                        Icons.Filled.SwapVert,
                        if (reversed) "正序" else "倒序",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            list == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            list.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("目录获取失败", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text("可能是源站改版或网络问题", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            val cur = n ?: return@OutlinedButton
                            retrying = true
                            NovelDirectoryFill.refillFromShelf(context.applicationContext, cur)
                            PluginUi.toast(context, "正在重新获取目录")
                            // 补取终态由 completed 流回推 load()；60 秒无终态则停转，不让按钮一直卡住。
                            scope.launch {
                                delay(60_000)
                                retrying = false
                            }
                        },
                        enabled = !retrying,
                    ) {
                        Text(if (retrying) "正在获取…" else "重新获取", fontSize = 13.sp)
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(display.orEmpty(), key = { it.index }) { ch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    ReaderJumpRequest.chapter = ch.index
                                    onOpenReader(novelId)
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ch.title.ifBlank { "第 ${ch.index + 1} 章" },
                                fontSize = 13.sp,
                                color = if (ch.index <= read) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (ch.index == read && n?.hasStartedReading == true) {
                                Text(
                                    "在读",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
