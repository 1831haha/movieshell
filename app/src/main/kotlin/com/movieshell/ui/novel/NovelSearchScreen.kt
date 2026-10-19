package com.movieshell.ui.novel

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.Button
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.TechDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.repo.NovelStore
import kotlinx.coroutines.launch

/**
 * v0.61.0 找书页：多书源并发真搜 → 加入书架（只拉目录，正文阅读时按需拉取）。
 * v0.64.0：JSON 书源 + 本地 PY 书源一起搜。
 * v0.66.0：支持锁定单个 PY 源（pySourceName 非空时只搜该源，用于书源管理点源直达）。
 */
@Composable
fun NovelSearchScreen(
    onBack: () -> Unit = {},
    onManageSources: () -> Unit = {},
    pySourceName: String? = null,
    embedded: Boolean = false, // v0.79.0：嵌入书架子 tab 时隐藏头部
) {
    // v0.103.0：activity 级 VM——切后台/切 tab 不销毁，viewModelScope 里的多书源搜索不中断、结果保留
    val activity = LocalContext.current as ComponentActivity
    val vm: NovelSearchViewModel = viewModel(activity)
    val gold = Color(0xFFD9C39A)

    val sources by BookSourceManager.sources.collectAsState()
    val pyRules by PyBookSourceManager.rules.collectAsState()
    val pyEnabled by PyBookSourceManager.enabled.collectAsState()
    val keyword by vm.keyword.collectAsState()
    val loading by vm.loading.collectAsState()
    val results by vm.results.collectAsState()
    val searched by vm.searched.collectAsState()
    val joining by vm.joining.collectAsState()
    // v0.67.0：小说搜索历史（独立作用域）
    val history by vm.history.collectAsState()

    // v0.108.0 B4：书架成员 id 快照——按钮按此切换"加入书架"/"已在书架"
    var shelfIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(results, joining) {
        // 结果刷新或一次加入结束 → 重拉书架成员，按钮状态实时跟上
        if (results.isNotEmpty() && joining == null) {
            shelfIds = NovelStore.list(appCtx).map { it.id }.toSet()
        }
    }
    // v0.108.0 B4：已在书架时点按钮 → 二次确认移出的目标
    var removeTarget by remember { mutableStateOf<Pair<SourceGroup, NovelSearchResult>?>(null) }

    // v0.66.0：锁定单 PY 源时只搜它（书源管理点源直达）；锁变化时 VM 清空旧结果
    val lockedPyRule = remember(pyRules, pySourceName) {
        pySourceName?.let { name -> pyRules.find { it.name == name } }
    }
    val totalEnabled = remember(sources, pyRules, pyEnabled) {
        sources.count { it.enabled } + pyRules.count { it.name in pyEnabled }
    }
    LaunchedEffect(pySourceName) { vm.setLockPyName(pySourceName) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!embedded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = gold)
            }
            Text(
                // v0.66.0：锁定单源时标题显示源名
                if (lockedPyRule != null) lockedPyRule.title else "找书",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = gold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // v0.66.0：锁定单源时不显示书源管理入口
            if (lockedPyRule == null) {
                TextButton(onClick = onManageSources) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Source, "书源管理", tint = gold, modifier = Modifier.padding(end = 4.dp))
                        Text("书源($totalEnabled)", color = gold, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { vm.setKeyword(it) },
                placeholder = { Text("书名 / 作者") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.doSearch() }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Filled.Search, "搜索", tint = gold)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("正在搜索 $totalEnabled 个书源…", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            searched && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没找到，换个关键词或检查书源规则", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !searched -> {
                // v0.67.0：搜索历史（一点即搜，X 单删）
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (totalEnabled == 0) "还没有启用的书源，点右上角去添加"
                            else "输入书名或作者开始搜索",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item(key = "history_header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "搜索历史",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = gold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    vm.clearHistory()
                                }) { Text("清空", fontSize = 13.sp) }
                            }
                        }
                        items(history, key = { "h_$it" }) { h ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.setKeyword(h)
                                        vm.doSearch()
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    h,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                IconButton(onClick = {
                                    vm.removeHistory(h)
                                }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                results.forEach { group ->
                    item(key = "header_${group.key}") {
                        Text(
                            "来自 ${group.name}（${group.books.size} 本）",
                            fontSize = 13.sp,
                            color = gold,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(group.books, key = { "${group.key}_${it.remoteId}" }) { item ->
                        AppCard {
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
                                Spacer(Modifier.width(8.dp))
                                val nid = shelfNovelId(group, item)
                                val onShelf = nid in shelfIds
                                val busy = joining == group.key + item.remoteId
                                Button(
                                    onClick = {
                                        if (onShelf) removeTarget = group to item
                                        else vm.joinShelf(group, item)
                                    },
                                    enabled = !busy,
                                ) {
                                    Text(if (busy) "加入中" else if (onShelf) "已在书架" else "加入书架")
                                }
                            }
                        }
                    }
                }
            }
            // v0.108.0 B4：已在书架点按钮 → 二次确认移出（影图风 TechDialog，文案走主题色）
            removeTarget?.let { (g, it) ->
                TechDialog(
                    onDismissRequest = { removeTarget = null },
                    title = "移出《${it.title}》？",
                    confirmText = "移出",
                    onConfirm = {
                        removeTarget = null
                        scope.launch {
                            val rid = shelfNovelId(g, it)
                            val ok = NovelStore.removeNovel(appCtx, rid)
                            if (ok) shelfIds = shelfIds - rid
                            Toast.makeText(
                                appCtx,
                                if (ok) "已移出书架" else "移出失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Text("将从书架移除，阅读进度也会清除。")
                }
            }
        }
    }
}

/** v0.64.0：找书结果分组（JSON 书源 / 本地 PY 书源统一展示）。v0.103.1：去 internal——
 * NovelSearchViewModel 的 public 成员 results/joinShelf 暴露它，internal 会编译失败。 */
data class SourceGroup(
    val key: String,
    val name: String,
    val books: List<NovelSearchResult>,
)

/** v0.108.0 B4：书架小说 id——拼法与 NovelSearchViewModel.joinShelf() 保持一致。 */
fun shelfNovelId(group: SourceGroup, item: NovelSearchResult): String =
    "bs_${group.key.hashCode()}_${item.remoteId.hashCode()}"
