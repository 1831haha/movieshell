package com.movieshell.ui.search

import com.movieshell.ui.theme.V5

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.V5Chip
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.components.PosterCard
import kotlinx.coroutines.launch


@Composable
fun SearchScreen(onVodClick: (vodId: String, siteKey: String) -> Unit) {
    // v0.271.0（T25）：进详情前的切源是 suspend，点卡片时放协程里等切完。
    val scope = rememberCoroutineScope()
    // v0.103.0：activity 级 VM——切后台/切 tab 不销毁，viewModelScope 里的聚合搜索不中断、结果保留
    val activity = LocalContext.current as ComponentActivity
    val vm: SearchViewModel = viewModel(activity)
    val query by vm.query.collectAsState()
    val submitted by vm.submitted.collectAsState()
    val history by vm.history.collectAsState()
    val focusManager = LocalFocusManager.current
    val searching by vm.searching.collectAsState()
    val groupedResults by vm.groupedResults.collectAsState() // null=尚未搜过
    // v0.192.0：左右分栏左栏与选中态（VM 侧，换词/切开关时随 doSearch 重置）。
    val searchSites by vm.searchSites.collectAsState()
    val completedKeys by vm.completedKeys.collectAsState()
    val selectedSiteKey by vm.selectedSiteKey.collectAsState()
    val skippedLabel by vm.skippedLabel.collectAsState()
    val failedLabel by vm.failedLabel.collectAsState()
    val onlyCurrentSource by vm.onlyCurrentSource.collectAsState()
    val currentSourceName by vm.currentSourceName.collectAsState()

    // 搜索历史：进入页面读一次，切回页面时刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        vm.refreshHistory()
        vm.refreshCurrentSourceName()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshHistory()
                vm.refreshCurrentSourceName()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun doSearch(text: String) {
        focusManager.clearFocus()
        vm.doSearch(text)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框（v0.27.0：AppTextField；左右配搜索/清空按钮，点放大镜即搜）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V5.Gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { doSearch(query) }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppTextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                placeholder = "搜索影片、剧集、演员…",
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch(query) }),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { vm.setQuery("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "清空")
                }
            }
        }

        // v0.189.0：搜索范围开关——所有源 / 只搜当前源（首页当前源口径：
        // 订阅当前源 > drpy > PY > PHP；切开关且已搜过时按新范围立即重搜）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V5.Gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            V5Chip(
                text = "所有源",
                selected = !onlyCurrentSource,
                onClick = { vm.setOnlyCurrentSource(false) },
                small = true,
            )
            V5Chip(
                text = "只搜当前源",
                selected = onlyCurrentSource,
                onClick = { vm.setOnlyCurrentSource(true) },
                small = true,
            )
            if (onlyCurrentSource) {
                Text(
                    text = currentSourceName?.let { "当前源：$it" } ?: "未设置当前源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // v0.87.0：搜索联想——输入时下拉匹配历史搜索词（忽略大小写，最多 8 条）
        val trimmedQuery = query.trim()
        val suggestions = remember(trimmedQuery, history) {
            if (trimmedQuery.isEmpty()) emptyList()
            else history.filter {
                it.contains(trimmedQuery, ignoreCase = true) &&
                    !it.equals(trimmedQuery, ignoreCase = true)
            }.take(8)
        }
        if (suggestions.isNotEmpty() && trimmedQuery != submitted) {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V5.Gutter),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    suggestions.forEach { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { doSearch(s) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = s,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            vm.clearHistory()
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("清空历史", fontSize = 12.sp) }
                }
            }
        }

        if (submitted.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = V5.Gutter),
            ) {
                // 搜索历史
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SectionHeader(icon = Icons.Filled.History, title = "搜索历史")
                        if (history.isNotEmpty()) {
                            TextButton(onClick = {
                                vm.clearHistory()
                            }) { Text("清空") }
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
                    items(history.distinct(), key = { it }) { h ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.setQuery(h); doSearch(h) },
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
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            IconButton(onClick = {
                                // v0.67.0：单条删除走 Store.remove
                                vm.removeHistory(h)
                            }) {
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
            // 聚合搜索结果：按源分组
            // v0.191.0：搜中途有分组就显示分组，不再拿骨架屏挡全部结果——
            // 骨架屏只在“一个分组都还没到”时出现；搜完且空才进空结果页。
            // 修复 v0.191.0 构建失败：groupedResults 是 by 委托属性，== null 后
            // 编译器不能对它做智能转换（第二次读取可能已变），先快照到局部变量再判空。
            val grouped = groupedResults
            if (grouped == null || (searching && grouped.isEmpty())) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(V5.Gutter),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "聚合搜索中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "先到的源会先显示，不用等全部搜完",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    repeat(4) {
                        LoadingSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(112.dp),
                        )
                    }
                }
            } else if (grouped.isNullOrEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // v0.162.0：全被黑名单跳过时，空结果也说明原因，别让用户对着空页猜
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        skippedLabel?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // v0.163.0：全被跳过时空页也给重试入口，别只剩“返回”
                            TextButton(onClick = { vm.retrySkipped() }) {
                                Text("重试被跳过的源")
                            }
                        }
                        failedLabel?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // v0.189.0：只搜当前源且无当前源时，空结果不是“没片”，
                        // 是范围里一个源都没有——说清楚，并给切回所有源的出口
                        if (onlyCurrentSource && currentSourceName == null) {
                            Text(
                                text = "当前没有设置首页源，先去站源管理设一个当前源，或切回所有源再搜",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { vm.setOnlyCurrentSource(false) }) {
                                Text("切回所有源")
                            }
                        }
                        // v0.191.0：只搜当前源且搜完为空时说清——不是“没搜”，
                        // 是该源确实 0 条（本轮 py:6v.py 对“我的”“手”都搜过、都 0 条）。
                        if (onlyCurrentSource && currentSourceName != null) {
                            Text(
                                text = "只搜当前源 $currentSourceName，已搜完，确实没有结果",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        EmptyState(
                            kind = EmptyKind.Empty,
                            icon = Icons.Filled.Search,
                            title = "没有找到「$submitted」相关结果",
                            actionText = "返回",
                            onAction = { vm.backToSearch() },
                        )
                    }
                }
            } else {
                // v0.192.0：左右分栏——左栏源列表（窄，不挤右栏），右栏视频网格。
                // 左栏按配置原顺序列出本次目标站，跑完的显示条数、没跑完的显示
                // “搜索中…”；点左栏切右栏，点“全部”回混排（分组先到先出）。
                val groups = grouped.orEmpty()
                val total = groups.sumOf { it.vods.size }
                val leftSites = if (searchSites.isNotEmpty()) searchSites else groups.map { it.site }
                val visibleGroups = selectedSiteKey?.let { key ->
                    groups.filter { it.site.key == key }
                } ?: groups
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = V5.Gutter,
                            vertical = 4.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = (if (searchSites.isNotEmpty()) "已搜 ${completedKeys.size}/${searchSites.size} · " else "") +
                            "共 $total 个结果 · ${groups.size} 个源" +
                            (if (onlyCurrentSource) " · 只搜当前源" else "") +
                            // v0.191.0：搜中途已显示的分组头上写明还在搜，别让用户
                            // 以为这就是全部结果；搜完后该后缀随 searching 落下而消失。
                            (if (searching) " · 还在搜…" else "") +
                            (skippedLabel?.let { " · $it" } ?: "") +
                            (failedLabel?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // v0.163.0：黑名单粘性问题的出口——点一下清掉黑名单、用当前词重搜
                    if (skippedLabel != null) {
                        TextButton(onClick = { vm.retrySkipped() }) {
                            Text("重试被跳过的源")
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    // 左栏：120dp 窄列，只放源名 + 状态，不抢右栏的海报宽度。
                    LazyColumn(
                        modifier = Modifier
                            .width(120.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    ) {
                        item(key = "left:all") {
                            LeftSourceItem(
                                name = "全部",
                                status = if (searching) "搜索中…" else "$total 个结果",
                                selected = selectedSiteKey == null,
                                onClick = { vm.selectSite(null) },
                            )
                        }
                        items(leftSites, key = { "left:${it.key}" }) { site ->
                            val done = site.key in completedKeys
                            val count = groups.firstOrNull { it.site.key == site.key }
                                ?.vods?.size
                            LeftSourceItem(
                                name = site.name,
                                status = if (done) "${count ?: 0} 条" else "搜索中…",
                                selected = selectedSiteKey == site.key,
                                onClick = { vm.selectSite(site.key) },
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    // 右栏：双列视频网格；点中某个源时只放该源的片。
                    if (visibleGroups.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (searching && selectedSiteKey !in completedKeys) {
                                    "该源还在搜，结果到了会显示在这里"
                                } else {
                                    "该源没有结果"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(V5.Gutter),
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            visibleGroups.forEach { group ->
                                val vods = group.vods.distinctBy { it.vodId }
                                items(vods, key = { "vod:${group.site.key}:${it.vodId}" }) { vod ->
                                    PosterCard(
                                        title = vod.vodName,
                                        // 全部混排时片下带源名，单源时源名已在左栏、不重复写。
                                        subtitle = if (selectedSiteKey == null) group.site.name else null,
                                        posterUrl = vod.vodPic,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(208.dp),
                                        onClick = {
                                            // v0.217.0（F51）：源已删时不进详情，只提示。
                                            scope.launch {
                                                if (!goDetail(group.site, vod.vodId, onVodClick)) {
                                                    AppToast.show(activity, "该结果的源已删除或不可用", long = false)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                            // v0.191.0：搜中途的页脚，告诉用户后面还会有分组陆续出现。
                            if (searching) {
                                item(span = { GridItemSpan(2) }) {
                                    Text(
                                        text = "还在搜其他源，有结果会陆续出现…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeftSourceItem(
    name: String,
    status: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // v0.192.0：左栏单行——源名最多 2 行，状态一行；选中行用主色底区分，
    // 不加粗、不放大，窄栏里不抢右栏海报的视觉。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(10.dp))
}

