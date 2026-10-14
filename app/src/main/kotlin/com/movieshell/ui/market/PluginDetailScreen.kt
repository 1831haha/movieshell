package com.movieshell.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.PluginMarket
import com.movieshell.data.repo.PluginStore
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch

/**
 * 插件详情页（v0.23.0）：图标/作者/版本/大小/标签/平台/完整描述/更新说明/安装按钮。
 * v0.27.0 Soft Tech 换皮：字阶统一 SoftType，空态走 EmptyState。逻辑与文案零变更。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(pluginId: String, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plugin = remember(pluginId) { PluginMarket.cached(pluginId) }
    var installedTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val installedVersion = remember(installedTick) {
        PluginStore.installedOf(pluginId)?.version
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件详情", style = SoftType.Title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { inner ->
        if (plugin == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    kind = EmptyKind.Error,
                    icon = Icons.Filled.ErrorOutline,
                    title = "插件不存在",
                    hint = "请返回重进市场页",
                    actionText = "返回",
                    onAction = onBack,
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SoftDimens.PagePadding, vertical = SoftDimens.CardSpacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (plugin.iconUrl.isNotBlank()) {
                    AsyncImage(
                        model = plugin.iconUrl,
                        contentDescription = plugin.name,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(SoftDimens.ButtonCorner))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = plugin.name, style = SoftType.Title)
                    if (plugin.author.isNotBlank()) {
                        Text(
                            text = "作者：${plugin.author}",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "v${plugin.version}",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(plugin.type) })
                if (plugin.category.isNotBlank()) {
                    AssistChip(onClick = {}, label = { Text(PluginUi.categoryLabel(plugin.category)) })
                }
                PluginMarket.formatSize(plugin.size).takeIf { it.isNotBlank() }?.let {
                    AssistChip(onClick = {}, label = { Text(it) })
                }
                if (plugin.platforms.isNotEmpty()) {
                    AssistChip(onClick = {}, label = { Text(plugin.platforms.joinToString("/")) })
                }
            }
            if (plugin.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    plugin.tags.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag) })
                    }
                }
            }
            if (plugin.desc.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(text = plugin.desc, style = SoftType.Body)
            }
            if (plugin.changelog.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(text = "更新说明", style = SoftType.CardTitle)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = plugin.changelog,
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            PluginActionButtons(
                plugin = plugin,
                installedVersion = installedVersion,
                busy = busy,
                onInstall = {
                    busy = true
                    scope.launch {
                        PluginUi.toast(context, PluginUi.install(context, plugin))
                        installedTick++
                        busy = false
                    }
                },
                onUninstall = {
                    busy = true
                    scope.launch {
                        PluginUi.toast(context, PluginUi.uninstall(context, plugin))
                        installedTick++
                        busy = false
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "server 插件 App 只负责下载校验与版本管理，Node.js 服务需自行运行",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
