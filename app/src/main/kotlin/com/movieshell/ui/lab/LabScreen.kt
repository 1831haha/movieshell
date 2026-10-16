package com.movieshell.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.SniffPatternStore
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch

/**
 * v0.15.0：实验室页面。
 *
 * - 插件市场入口：浏览/安装/卸载市场里的插件（v0.27.4 从站源页移过来；
 *   v0.47.0：已安装插件区删除，安装卸载统一在市场里做）
 * - v0.16.1：嗅探自定义正则管理（对标 DsPlayer"扩展正则"）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(
    onBack: () -> Unit,
    // v0.27.4：插件市场入口从站源页移过来
    onMarketClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var showAddPattern by remember { mutableStateOf(false) }
    var patterns by remember { mutableStateOf(SniffPatternStore.list()) }

    fun refreshPatterns() {
        patterns = SniffPatternStore.list()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // v0.39.0：mockup 风格香槟金标题
                title = { Text("实验室", style = SoftType.Title, color = Color(0xFFD9C39A)) },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
        ) {
            // v0.27.4：插件市场入口（从站源页移过来）
            item {
                SectionTitle(title = "插件")
                Spacer(Modifier.height(8.dp))
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onMarketClick),
                ) {
                    Row(
                        modifier = Modifier.padding(SoftDimens.CardSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(SoftDimens.CardSpacing))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "插件市场",
                                style = SoftType.CardTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "浏览并安装插件（环境包 / 服务 / 直播源）",
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            // v0.47.0：已安装插件区删除——安装/卸载统一收进插件市场，实验室只留入口
            // v0.16.1：嗅探自定义正则
            item {
                SectionTitle(
                    title = "嗅探正则",
                    action = {
                        TextButton(onClick = { showAddPattern = true }) {
                            Text("添加")
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "匹配到的 URL 直接视为可播直链（如：\\.m3u8\\?.*token=）",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (patterns.isEmpty()) {
                item {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Tune,
                        title = "暂无自定义正则",
                        hint = "添加后，匹配到的 URL 直接视为可播直链",
                    )
                }
            } else {
                items(patterns) { pattern ->
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = SoftDimens.CardSpacing,
                                vertical = 4.dp,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = pattern,
                                style = SoftType.Body,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    LogBus.i("UI", "删除嗅探正则: $pattern")
                                    SniffPatternStore.remove(pattern)
                                    refreshPatterns()
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加正则对话框
    if (showAddPattern) {
        var input by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAddPattern = false },
            title = { Text("添加嗅探正则", style = SoftType.Title) },
            text = {
                Column {
                    Text(
                        text = "正则表达式",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            error = null
                        },
                        placeholder = "\\.m3u8\\?.*",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = SoftType.Caption,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (SniffPatternStore.add(input)) {
                            LogBus.i("UI", "添加嗅探正则: $input")
                            refreshPatterns()
                            showAddPattern = false
                        } else {
                            error = "正则格式无效或已存在"
                        }
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPattern = false }) {
                    Text("取消")
                }
            },
        )
    }
}

