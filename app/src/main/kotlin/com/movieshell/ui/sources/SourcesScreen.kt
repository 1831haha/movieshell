package com.movieshell.ui.sources

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 站源条目：开关状态用 mutableStateOf 包裹，保证 Switch 切换即时刷新 */
class SourceEntry(
    val name: String,
    val detail: String,
    enabled: Boolean = true,
) {
    var enabled by mutableStateOf(enabled)
}

/** 站源分组 */
class SourceGroup(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val entries: SnapshotStateList<SourceEntry>,
) {
    var expanded by mutableStateOf(true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen() {
    val groups = remember {
        mutableStateListOf(
            SourceGroup(
                id = "api", name = "接口站源", icon = Icons.Filled.Language,
                entries = mutableStateListOf(
                    SourceEntry("量子资源", "https://cj.lziapi.com/api.php/provide/vod/"),
                    SourceEntry("天空资源", "https://api.tiankongapi.com/api.php/provide/vod/"),
                    SourceEntry("暴风资源", "https://bfzyapi.com/api.php/provide/vod/", enabled = false),
                ),
            ),
            SourceGroup(
                id = "js", name = "JS文件夹", icon = Icons.Filled.Code,
                entries = mutableStateListOf(
                    SourceEntry("douban.js", "drpy 规则 · 128 KB"),
                    SourceEntry("bilibili.js", "drpy 规则 · 96 KB", enabled = false),
                    SourceEntry("ikanbot.js", "drpy 规则 · 74 KB"),
                ),
            ),
            SourceGroup(
                id = "py", name = "PY文件夹", icon = Icons.Filled.Terminal,
                entries = mutableStateListOf(
                    SourceEntry("btsow_spider.py", "hipy 规则 · 21 KB"),
                    SourceEntry("douban_hot.py", "hipy 规则 · 9 KB"),
                ),
            ),
            SourceGroup(
                id = "ai", name = "AI文件夹", icon = Icons.Filled.AutoAwesome,
                entries = mutableStateListOf(),
            ),
            SourceGroup(
                id = "php", name = "PHP文件夹", icon = Icons.Filled.Extension,
                entries = mutableStateListOf(
                    SourceEntry("cms_ali.php", "cms 采集 · 12 KB", enabled = false),
                ),
            ),
        )
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }

    fun addSubscription() {
        val url = urlInput.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            urlError = true
            return
        }
        val name = runCatching {
            url.substringAfter("://").substringBefore("/").substringBefore(":")
        }.getOrDefault("自定义订阅").ifBlank { "自定义订阅" }
        groups.first { it.id == "api" }.entries.add(0, SourceEntry(name, url))
        urlInput = ""
        urlError = false
        showAddDialog = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部"添加订阅"按钮
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加订阅")
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(groups, key = { it.id }) { group ->
                GroupCard(
                    group = group,
                    onRemove = { entry -> group.entries.remove(entry) },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加订阅") },
            text = {
                Column {
                    Text(
                        text = "输入站源订阅地址（CMS 接口 JSON）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it; urlError = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…/api.php/provide/vod/") },
                        singleLine = true,
                        isError = urlError,
                        supportingText = if (urlError) {
                            { Text("请输入以 http:// 或 https:// 开头的有效地址") }
                        } else null,
                    )
                }
            },
            confirmButton = {
                Button(onClick = ::addSubscription) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun GroupCard(
    group: SourceGroup,
    onRemove: (SourceEntry) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 分组头：图标 + 名称 + 文件数徽标 + 展开/收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = group.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                BadgedBox(badge = { Badge { Text(group.entries.size.toString()) } }) {
                    Spacer(Modifier.size(4.dp))
                }
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = { group.expanded = !group.expanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (group.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (group.expanded) "收起" else "展开",
                    )
                }
            }

            AnimatedVisibility(visible = group.expanded) {
                Column {
                    if (group.entries.isEmpty()) {
                        Text(
                            text = "该分组暂无文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else {
                        group.entries.forEach { entry ->
                            SourceEntryRow(entry = entry, onRemove = { onRemove(entry) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceEntryRow(
    entry: SourceEntry,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Switch(
            checked = entry.enabled,
            onCheckedChange = { entry.enabled = it },
        )
    }
}
