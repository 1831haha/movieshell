package com.movieshell.ui.download

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.download.DownloadManager
import com.movieshell.data.download.Notify
import com.movieshell.data.repo.DlStatus
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.DownloadTask
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch
import java.io.File

/**
 * 下载管理（v0.26.0）。
 *
 * 进行中：进度条 + 暂停/继续/删除；已完成：点播本地文件/删除。
 * m3u8 下载到 30% 后可"边下边播"（播本地 index.m3u8）。
 *
 * v0.27.0 Soft Tech 换皮：任务卡走 AppCard，分区标题走 SectionTitle，
 * 空态走 EmptyState，按钮走 AppButton，进度条走主色。逻辑与文案零变更。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onPlayLocal: (path: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Notify.RequestNotificationPermissionOnce()
    val tasks by DownloadStore.tasks.collectAsState()
    var confirmDelete by remember { mutableStateOf<DownloadTask?>(null) }

    if (confirmDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = RoundedCornerShape(SoftDimens.CardCorner),
            title = { Text("删除任务", style = SoftType.CardTitle) },
            text = { Text("同时删除已下载的文件？", style = SoftType.Body) },
            confirmButton = {
                AppButton(
                    text = "删除文件",
                    onClick = {
                        val t = confirmDelete!!
                        confirmDelete = null
                        scope.launch { DownloadManager.delete(context, t.id) }
                    },
                    variant = AppButtonVariant.Secondary,
                )
            },
            dismissButton = {
                AppButton(
                    text = "仅移除记录",
                    onClick = {
                        val t = confirmDelete!!
                        confirmDelete = null
                        scope.launch {
                            DownloadStore.remove(context, t.id, deleteFiles = false)
                        }
                    },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }

    val active = tasks.filter { it.status != DlStatus.DONE }
    val done = tasks.filter { it.status == DlStatus.DONE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载管理", style = SoftType.Title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (done.isNotEmpty()) {
                        AppButton(
                            text = "清空已完成",
                            onClick = {
                                scope.launch { DownloadStore.clearDone(context) }
                            },
                            variant = AppButtonVariant.Ghost,
                        )
                    }
                },
            )
        },
    ) { inner ->
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    kind = EmptyKind.Empty,
                    icon = Icons.Filled.Download,
                    title = "暂无下载任务",
                    hint = "在详情页选集下载",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(SoftDimens.CardSpacing),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (active.isNotEmpty()) {
                    item {
                        SectionTitle(title = "下载中（${active.size}）")
                    }
                    items(active, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onPauseResume = {
                                if (task.status == DlStatus.DOWNLOADING || task.status == DlStatus.QUEUED) {
                                    DownloadManager.pause(context, task.id)
                                } else {
                                    DownloadManager.resume(context, task.id)
                                    Toast.makeText(context, "已加入下载队列", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = { confirmDelete = task },
                            onPreview = {
                                val path = previewPath(context, task)
                                if (path != null) onPlayLocal(path, task.title)
                                else Toast.makeText(context, "还没下到可播的部分", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
                if (done.isNotEmpty()) {
                    item {
                        SectionTitle(title = "已完成（${done.size}）")
                    }
                    items(done, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onPauseResume = null,
                            onDelete = { confirmDelete = task },
                            onPreview = null,
                            onPlay = {
                                val p = task.localPath
                                if (p.isNotBlank() && File(p).isFile) onPlayLocal(p, task.title)
                                else Toast.makeText(context, "本地文件已丢失", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 边下边播路径：m3u8 播本地 index.m3u8（30% 后），直链播 .part 文件。 */
private fun previewPath(context: android.content.Context, task: DownloadTask): String? {
    if (task.status == DlStatus.DONE) return task.localPath.takeIf { it.isNotBlank() }
    if (task.progress < 0.3f) return null
    val dir = DownloadStore.taskDir(context, task)
    return when (task.kind) {
        "m3u8" -> File(dir, "index.m3u8").takeIf { it.isFile }?.absolutePath
        else -> dir.listFiles { f -> f.name.endsWith(".part") }
            ?.firstOrNull()?.absolutePath
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    onPauseResume: (() -> Unit)?,
    onDelete: () -> Unit,
    onPreview: (() -> Unit)?,
    onPlay: (() -> Unit)? = null,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = SoftType.CardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText(task),
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onPlay != null) {
                    IconButton(
                        onClick = onPlay,
                        modifier = Modifier.size(SoftDimens.TouchTarget),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
                    }
                }
                if (onPreview != null && task.status != DlStatus.DONE && task.progress >= 0.3f) {
                    AppButton(
                        text = "边下边播",
                        onClick = onPreview,
                        variant = AppButtonVariant.Ghost,
                    )
                }
                if (onPauseResume != null) {
                    IconButton(
                        onClick = onPauseResume,
                        modifier = Modifier.size(SoftDimens.TouchTarget),
                    ) {
                        Icon(
                            if (task.status == DlStatus.DOWNLOADING || task.status == DlStatus.QUEUED)
                                Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "暂停/继续",
                        )
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(SoftDimens.TouchTarget),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (task.status != DlStatus.DONE) {
                LinearProgressIndicator(
                    progress = task.progress.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

private fun statusText(task: DownloadTask): String = when (task.status) {
    DlStatus.DOWNLOADING -> {
        // v0.41.0：带实时速度
        val speed = if (task.speedBps > 0) " · ${fmtSpeed(task.speedBps)}" else ""
        if (task.kind == "m3u8") {
            "下载中 ${task.doneSeg}/${task.totalSeg} 分片（${(task.progress * 100).toInt()}%）$speed"
        } else {
            "下载中 ${(task.progress * 100).toInt()}%$speed"
        }
    }
    DlStatus.QUEUED -> "排队等待"
    DlStatus.PAUSED -> "已暂停（${(task.progress * 100).toInt()}%）"
    DlStatus.FAILED -> "失败：${task.error.ifBlank { "未知错误" }}，点▶重试"
    DlStatus.DONE -> "已完成"
    else -> task.status
}

/** v0.41.0：B/s → 人话。 */
private fun fmtSpeed(bps: Long): String = when {
    bps < 1024 -> "$bps B/s"
    bps < 1024 * 1024 -> "%.1f KB/s".format(bps / 1024f)
    else -> "%.1f MB/s".format(bps / 1024f / 1024f)
}
