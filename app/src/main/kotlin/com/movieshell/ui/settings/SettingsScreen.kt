package com.movieshell.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contracts.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.BackupManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onManualClick: () -> Unit = {}) {
    val context = LocalContext.current
    var theme by remember { mutableStateOf("深色") }
    var showThemeDialog by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf("内置播放器（ExoPlayer）") }
    var showPlayerDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf("128 MB") }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // 备份/恢复 launcher：必须在 onClick 引用之前声明
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(BackupManager.exportJson(context).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(context, "备份已导出", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: return@rememberLauncherForActivityResult
            when (val r = BackupManager.importJson(context, text)) {
                is BackupManager.RestoreResult.Ok ->
                    Toast.makeText(context, "恢复成功", Toast.LENGTH_SHORT).show()
                is BackupManager.RestoreResult.Failed ->
                    Toast.makeText(context, "恢复失败：${r.reason}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "恢复失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "外观") {
                SettingsRow(
                    icon = Icons.Filled.Palette,
                    title = "主题",
                    subtitle = theme,
                    onClick = { showThemeDialog = true },
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "播放") {
                SettingsRow(
                    icon = Icons.Filled.PlayCircle,
                    title = "默认播放器",
                    subtitle = player,
                    onClick = { showPlayerDialog = true },
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "存储") {
                SettingsRow(
                    icon = Icons.Filled.Cached,
                    title = "清理缓存",
                    subtitle = "共占用 $cacheSize",
                    onClick = { showClearCacheDialog = true },
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "备份与恢复") {
                SettingsRow(
                    icon = Icons.Filled.Backup,
                    title = "备份到文件",
                    subtitle = "导出订阅与规则配置为 JSON 文件",
                    onClick = {
                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        exportLauncher.launch("movieshell_backup_$ts.json")
                    },
                )
                SettingsRow(
                    icon = Icons.Filled.Restore,
                    title = "从文件恢复",
                    subtitle = "从 JSON 备份文件恢复订阅与规则",
                    onClick = { importLauncher.launch("application/json") },
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "帮助") {
                SettingsRow(
                    icon = Icons.Filled.MenuBook,
                    title = "使用手册",
                    subtitle = "加站源 / 规则 / 磁力，看这篇就够了",
                    onClick = onManualClick,
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "关于") {
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "关于 MovieShell",
                    subtitle = "版本 ${com.movieshell.BuildConfig.VERSION_NAME} · 本地影视聚合",
                    onClick = { /* 占位：可跳转关于页 */ },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = "选择主题",
            options = listOf("跟随系统", "深色", "浅色"),
            selected = theme,
            onDismiss = { showThemeDialog = false },
            onSelect = { theme = it; showThemeDialog = false },
        )
    }
    if (showPlayerDialog) {
        SingleChoiceDialog(
            title = "默认播放器",
            options = listOf("内置播放器（ExoPlayer）", "外部播放器"),
            selected = player,
            onDismiss = { showPlayerDialog = false },
            onSelect = { player = it; showPlayerDialog = false },
        )
    }
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("清理缓存") },
            text = { Text("确定要清理 $cacheSize 缓存吗？播放记录与站源配置不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    cacheSize = "0 MB" // 占位：实际接入 CacheManager 后执行真实清理
                    showClearCacheDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(option) },
                        trailingContent = {
                            RadioButton(
                                selected = option == selected,
                                onClick = { onSelect(option) },
                            )
                        },
                        modifier = Modifier.clickable { onSelect(option) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
