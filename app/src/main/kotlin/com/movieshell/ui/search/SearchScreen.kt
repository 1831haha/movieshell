package com.movieshell.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movieshell.data.model.Vod as ModelVod
import com.movieshell.data.repo.AiSourceManager
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.ui.components.GradientBlock
import com.movieshell.ui.data.DemoData
import com.movieshell.ui.data.Vod
import kotlinx.coroutines.launch

/** drpy 影片 -> 界面 Vod */
private fun ModelVod.toUiVod() = Vod(
    id = vodId,
    title = vodName,
    category = typeName,
    year = vodYear,
    rating = vodRemarks,
    area = "",
    actors = "",
    desc = "",
    episodes = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onVodClick: (vodId: String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // ---- drpy / PY 规则接入 ----
    var drpyActive by remember { mutableStateOf(false) }
    var drpySearching by remember { mutableStateOf(false) }
    var drpyResults by remember { mutableStateOf<List<Vod>?>(null) } // null=尚未搜过

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                drpyActive = DrpySourceManager.hasActiveRule() || PySourceManager.hasActiveRule() ||
                    AiSourceManager.hasActiveRule()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun doSearch(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        submitted = t
        history.remove(t)
        history.add(0, t)
        if (history.size > 10) history.removeAt(history.lastIndex)
        focusManager.clearFocus()
        if (drpyActive) {
            // 有激活的 drpy/PY 规则：走规则搜索（drpy 优先）
            drpySearching = true
            drpyResults = null
            scope.launch {
                val list = runCatching {
                    if (DrpySourceManager.hasActiveRule()) DrpySourceManager.search(t)
                    else if (PySourceManager.hasActiveRule()) PySourceManager.search(t)
                    else AiSourceManager.search(t)
                }.getOrDefault(emptyList())
                drpyResults = list.map { it.toUiVod() }
                drpySearching = false
            }
        }
    }

    // 演示数据搜索（无激活规则时用）
    val demoResults: List<Vod> = remember(submitted) {
        if (submitted.isBlank()) emptyList()
        else DemoData.vods.filter {
            it.title.contains(submitted) || it.actors.contains(submitted) || it.category.contains(submitted)
        }
    }
    val results: List<Vod>? = if (drpyActive) drpyResults else demoResults

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("搜索影片、剧集、演员…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "搜索") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch(query) }),
        )

        if (submitted.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                // 热搜词
                item {
                    SectionHeader(icon = Icons.Filled.TrendingUp, title = "热搜")
                    HotSearchFlow(
                        words = DemoData.hotSearches,
                        onPick = { query = it; doSearch(it) },
                    )
                    Spacer(Modifier.height(20.dp))
                }
                // 搜索历史
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SectionHeader(icon = Icons.Filled.History, title = "搜索历史")
                        if (history.isNotEmpty()) {
                            TextButton(onClick = { history.clear() }) { Text("清空") }
                        }
                    }
                }
                if (history.isEmpty()) {
                    item {
                        Text(
                            text = "暂无搜索历史",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    items(history, key = { it }) { h ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = h,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(onClick = { history.remove(h) }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 搜索结果：drpy 搜索中显示加载圈
            if (drpyActive && (drpySearching || results == null)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "搜索中…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (results.isNullOrEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "没有找到「$submitted」相关结果",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { submitted = ""; query = "" }) { Text("返回") }
                }
            } else {
                Text(
                    text = "找到 ${results.size} 个结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results.orEmpty(), key = { it.id }) { vod ->
                        ListItem(
                            headlineContent = {
                                Text(vod.title, fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Text("${vod.category} · ${vod.year} · ${vod.rating}分 · ${vod.actors}")
                            },
                            leadingContent = {
                                GradientBlock(
                                    key = vod.id,
                                    modifier = Modifier
                                        .size(width = 48.dp, height = 68.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                ) {
                                    Text(
                                        text = vod.title.first().toString(),
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { onVodClick(vod.id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "播放",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onVodClick(vod.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HotSearchFlow(
    words: List<String>,
    onPick: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        words.forEachIndexed { index, word ->
            SuggestionChip(
                onClick = { onPick(word) },
                label = {
                    Text(
                        text = "${index + 1}. $word",
                        color = if (index < 3) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}
