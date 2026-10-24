package com.movieshell.ui.search

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
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
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.components.PosterCard
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType


@Composable
fun SearchScreen(onVodClick: (vodId: String) -> Unit) {
    // v0.103.0：activity 级 VM——切后台/切 tab 不销毁，viewModelScope 里的聚合搜索不中断、结果保留
    val activity = LocalContext.current as ComponentActivity
    val vm: SearchViewModel = viewModel(activity)
    val query by vm.query.collectAsState()
    val submitted by vm.submitted.collectAsState()
    val history by vm.history.collectAsState()
    val focusManager = LocalFocusManager.current
    val searching by vm.searching.collectAsState()
    val groupedResults by vm.groupedResults.collectAsState() // null=尚未搜过
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
                .padding(horizontal = SoftDimens.PagePadding, vertical = 12.dp),
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
                .padding(horizontal = SoftDimens.PagePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppChip(
                text = "所有源",
                selected = !onlyCurrentSource,
                onClick = { vm.setOnlyCurrentSource(false) },
                small = true,
            )
            AppChip(
                text = "只搜当前源",
                selected = onlyCurrentSource,
                onClick = { vm.setOnlyCurrentSource(true) },
                small = true,
            )
            if (onlyCurrentSource) {
                Text(
                    text = currentSourceName?.let { "当前源：$it" } ?: "未设置当前源",
                    style = SoftType.Caption,
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
                    .padding(horizontal = SoftDimens.PagePadding),
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
                                style = SoftType.Body,
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
                contentPadding = PaddingValues(horizontal = SoftDimens.PagePadding),
            ) {
                // 热搜词（v0.27.6：假热搜词下线，无数据时隐藏该区）
                val hotWords: List<String> = emptyList()
                if (hotWords.isNotEmpty()) {
                    item {
                        SectionHeader(icon = Icons.Filled.TrendingUp, title = "热搜")
                        HotSearchFlow(
                            words = hotWords,
                            onPick = { vm.setQuery(it); doSearch(it) },
                        )
                        Spacer(Modifier.height(20.dp))
                    }
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
                            style = SoftType.Body,
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
                                style = SoftType.Body,
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
                        .padding(SoftDimens.PagePadding),
                    verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                ) {
                    Text(
                        text = "聚合搜索中…",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "先到的源会先显示，不用等全部搜完",
                        style = SoftType.Caption,
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
                                style = SoftType.Caption,
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
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // v0.189.0：只搜当前源且无当前源时，空结果不是“没片”，
                        // 是范围里一个源都没有——说清楚，并给切回所有源的出口
                        if (onlyCurrentSource && currentSourceName == null) {
                            Text(
                                text = "当前没有设置首页源，先去站源管理设一个当前源，或切回所有源再搜",
                                style = SoftType.Caption,
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
                                style = SoftType.Caption,
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
                val groups = grouped.orEmpty()
                val total = groups.sumOf { it.vods.size }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = SoftDimens.PagePadding,
                            vertical = 4.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "共 $total 个结果 · ${groups.size} 个源" +
                            (if (onlyCurrentSource) " · 只搜当前源" else "") +
                            // v0.191.0：搜中途已显示的分组头上写明还在搜，别让用户
                            // 以为这就是全部结果；搜完后该后缀随 searching 落下而消失。
                            (if (searching) " · 还在搜…" else "") +
                            (skippedLabel?.let { " · $it" } ?: "") +
                            (failedLabel?.let { " · $it" } ?: ""),
                        style = SoftType.Caption,
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groups.forEach { group ->
                        // 分组头：源名 + 条数
                        item(key = "head:${group.site.key}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = SoftDimens.PagePadding,
                                        vertical = 10.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = group.site.name,
                                    style = SoftType.CardTitle,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "${group.vods.size} 条",
                                    style = SoftType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val uiVods = group.vods.map { it.toUiVod() }.distinctBy { it.id }
                        items(uiVods, key = { "vod:${group.site.key}:${it.id}" }) { vod ->
                            AppCard(
                                onClick = { goDetail(group.site, vod.id, onVodClick) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = SoftDimens.PagePadding,
                                        vertical = 4.dp,
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(SoftDimens.CardSpacing),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PosterCard(
                                        title = vod.title,
                                        posterUrl = vod.poster,
                                        modifier = Modifier
                                            .width(72.dp)
                                            .height(104.dp),
                                    )
                                    Spacer(Modifier.width(SoftDimens.CardSpacing))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = vod.title,
                                            style = SoftType.CardTitle,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        if (vod.desc.isNotBlank()) {
                                            Text(
                                                text = vod.desc,
                                                style = SoftType.Caption,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.height(4.dp))
                                        }
                                        Text(
                                            text = "${vod.category} · ${vod.year}",
                                            style = SoftType.Body,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "${vod.rating}分 · ${vod.actors}",
                                            style = SoftType.Caption,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(onClick = { goDetail(group.site, vod.id, onVodClick) }) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "播放",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // v0.191.0：搜中途的页脚，告诉用户后面还会有分组陆续出现。
                    if (searching) {
                        item(key = "searching-footer") {
                            Text(
                                text = "还在搜其他源，有结果会陆续出现…",
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = SoftDimens.PagePadding,
                                        vertical = 12.dp,
                                    ),
                            )
                        }
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
            style = SoftType.CardTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
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
            AppChip(
                text = "${index + 1}. $word",
                selected = index < 3,
                onClick = { onPick(word) },
            )
        }
    }
}
