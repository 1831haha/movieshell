package com.movieshell.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.BackupManager
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.LogStore
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.ThemeStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onManualClick: () -> Unit = {}) {
    val context = LocalContext.current
    // v0.7.1：主题读持久化 ThemeStore，不再是本地假状态
    val themeMode by ThemeStore.mode.collectAsState()
    val themeLabel = when (themeMode) {
        ThemeStore.MODE_DARK -> "深色"
        ThemeStore.MODE_LIGHT -> "浅色"
        else -> "跟随系统"
    }
    var showThemeDialog by remember { mutableStateOf(false) }
    var backupExpanded by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf("内置播放器（ExoPlayer）") }
    var showPlayerDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf("128 MB") }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // v0.7.2：备份直接导出到 xiaying/backup/，不再弹系统文件选择器
    fun exportBackup() {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(StoragePaths.backupDir(context), "movieshell_backup_$ts.json")
            file.writeText(BackupManager.exportJson(context))
            LogBus.i("Backup", "备份已导出：${file.absolutePath}")
            Toast.makeText(context, "备份已导出：${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            LogBus.e("Backup", "备份导出失败：${e.message}")
            Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    // 恢复仍需用户选文件，保留 picker
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
                    subtitle = themeLabel,
                    onClick = { showThemeDialog = true },
                )
                // v0.12.0：主题色换色器（DsPlayer 式色圈）
                ThemeColorPickerRow()
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
                    title = "备份与恢复",
                    subtitle = "导出 / 导入 JSON 备份文件",
                    onClick = { backupExpanded = !backupExpanded },
                    trailing = {
                        Icon(
                            imageVector = if (backupExpanded) Icons.Filled.ExpandLess
                            else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                AnimatedVisibility(visible = backupExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        SettingsRow(
                            icon = Icons.Filled.Backup,
                            title = "备份到文件",
                            subtitle = "导出到 /storage/emulated/0/xiaying/backup/",
                            onClick = { exportBackup() },
                        )
                        SettingsRow(
                            icon = Icons.Filled.Restore,
                            title = "从文件恢复",
                            subtitle = "从 JSON 备份文件恢复订阅与规则",
                            onClick = { importLauncher.launch("application/json") },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "调试") {
                val logEnabled by LogStore.enabled.collectAsState()
                SettingsRow(
                    icon = Icons.Filled.BugReport,
                    title = "运行日志悬浮窗",
                    subtitle = if (logEnabled) "已开启：小泡可拖动，点开看日志" else "关闭",
                    onClick = { LogStore.setEnabled(context, !logEnabled) },
                    trailing = {
                        Switch(
                            checked = logEnabled,
                            onCheckedChange = { LogStore.setEnabled(context, it) },
                        )
                    },
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
            selected = themeLabel,
            onDismiss = { showThemeDialog = false },
            onSelect = {
                val mode = when (it) {
                    "深色" -> ThemeStore.MODE_DARK
                    "浅色" -> ThemeStore.MODE_LIGHT
                    else -> ThemeStore.MODE_SYSTEM
                }
                ThemeStore.setMode(context, mode)
                showThemeDialog = false
            },
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
    trailing: @Composable (() -> Unit)? = null,
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
        trailingContent = trailing ?: {
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

/**
 * v0.12.0：主题色换色器。DsPlayer 式横向色圈，点选即全 App 换主色。
 */
@Composable
private fun ThemeColorPickerRow() {
    val context = LocalContext.current
    val selectedColor by ThemeStore.color.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "主题色",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeStore.COLOR_OPTIONS.forEach { argb ->
                val isSelected = argb == selectedColor
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .clickable { ThemeStore.setColor(context, argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "已选",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
