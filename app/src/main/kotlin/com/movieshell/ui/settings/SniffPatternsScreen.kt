package com.movieshell.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.SniffPatternStore
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType

/**
 * v0.79.0：嗅探自定义正则管理（原实验室页，实验室 tab 删除后搬到设置→源管理）。
 *
 * 匹配到的 URL 直接视为可播直链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SniffPatternsScreen(onBack: () -> Unit = {}) {
    var showAddPattern by remember { mutableStateOf(false) }
    var patterns by remember { mutableStateOf(SniffPatternStore.list()) }

    fun refreshPatterns() {
        patterns = SniffPatternStore.list()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            TabTopBar(
                title = "嗅探正则管理",
                onBack = onBack,
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
        ) {
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
                    text = "匹配到的 URL 直接视为可播直链（如：\\.m3u8\\?.*token=.*）",
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
                items(patterns, key = { it }) { pattern ->
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

    if (showAddPattern) {
        var input by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        TechDialog(
            onDismissRequest = { showAddPattern = false },
            title = "添加嗅探正则",
            confirmText = "确定",
            onConfirm = {
                if (SniffPatternStore.add(input)) {
                    LogBus.i("UI", "添加嗅探正则: $input")
                    refreshPatterns()
                    showAddPattern = false
                } else {
                    error = "正则格式无效或已存在"
                }
            },
        ) {
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
        }
    }
}
