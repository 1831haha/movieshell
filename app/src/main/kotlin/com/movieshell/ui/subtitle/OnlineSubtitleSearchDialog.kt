package com.movieshell.ui.subtitle

import com.movieshell.ui.components.AppSearchBar
import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.LogBus
import com.movieshell.data.source.subtitle.OnlineSubtitle
import com.movieshell.data.source.subtitle.OnlineSubtitleKit
import com.movieshell.data.source.subtitle.SubtitleSources
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppTextField
import com.movieshell.player.PlayerDialogCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 在线字幕搜索对话框（v0.28.0，详情页/播放器共用）。
 *
 * @param keyword 初始关键词（影片标题）
 * @param vodId 剧 ID（下载后按剧记忆，可空）
 * @param onPick 选中下载后回调 file:// 地址（调用方负责应用到播放器）
 */
@Composable
fun OnlineSubtitleSearchDialog(
    keyword: String,
    vodId: String?,
    onPick: (fileUrl: String) -> Unit,
    onDismiss: () -> Unit,
    sourceKey: String? = null,
    episodeIndex: Int? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v0.30.0：字幕源由插件动态提供
    val source = remember { SubtitleSources.default(context) }
    var query by remember { mutableStateOf(keyword) }
    var searching by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    // v0.49.0：SubHD 内置，source 恒不为 null；保留防御分支
    if (source == null) {
        LaunchedEffect(Unit) {
            AppToast.show(context, "字幕源不可用", long = false)
            onDismiss()
        }
        return
    }

    fun doSearch(q: String) {
        val k = q.trim()
        if (k.isEmpty() || searching) return
        searching = true
        searched = false
        scope.launch {
            results = runCatching { source.search(k) }.onFailure {
                // v0.247.0（F43）：搜索被取消（关对话框）时原样抛出，不当成
                // 搜索失败弹 Toast、不翻成“没搜到”；取消后下面的两行不执行。
                if (it is CancellationException) throw it
                LogBus.w("Subtitle", "在线字幕搜索失败：${it.message}")
                AppToast.show(context, "搜索失败：${it.message}", long = false)
            }.getOrDefault(emptyList())
            searching = false
            searched = true
        }
    }

    // 打开即搜一次
    LaunchedEffect(Unit) { doSearch(query) }

    PlayerDialogCard(
        title = "在线字幕（${source.name}）",
        onDismiss = onDismiss,
        buttons = {
            AppButton(text = "关闭", onClick = onDismiss, variant = AppButtonVariant.Ghost)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v0.294.6：统一 AppSearchBar（回车即搜）
            AppSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "影片标题",
                onSearch = { if (!searching) doSearch(it) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            AppButton(
                text = "搜索",
                onClick = { doSearch(query) },
                enabled = !searching,
            )
        }
        Spacer(Modifier.height(12.dp))
        when {
            searching -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
            downloading -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text("下载中…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            results.isEmpty() && searched -> {
                Text(
                    "没搜到，换个关键词试试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            results.isNotEmpty() -> {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    // v0.133.0：sid 只在来源内唯一（下载文件名同为来源加 sid 口径），key 带 sourceId 复合
                    items(results, key = { "${it.sourceId}_${it.sid}" }) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !downloading) {
                                    downloading = true
                                    scope.launch {
                                        runCatching {
                                            OnlineSubtitleKit
                                                .downloadAndSave(context, source, item, vodId, sourceKey, episodeIndex)
                                        }.onSuccess { url ->
                                            onPick(url)
                                        }.onFailure {
                                            // v0.247.0（F43 同类）：下载被取消时原样抛出，
                                            // 不当成下载失败弹 Toast。
                                            if (it is CancellationException) throw it
                                            LogBus.w("Subtitle", "字幕下载失败：${it.message}")
                                            AppToast.show(context, "下载失败：${it.message}", long = false)
                                        }
                                        downloading = false
                                    }
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val meta = listOf(item.language, item.format, item.size, item.downloads)
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "下载的字幕自动按剧记忆，下次播放直接挂载",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
