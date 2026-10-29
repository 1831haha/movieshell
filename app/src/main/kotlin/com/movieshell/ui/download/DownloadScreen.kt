package com.movieshell.ui.download

import com.movieshell.ui.theme.V4
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
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
import com.movieshell.data.repo.DownloadSettings
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.DownloadTask
import com.movieshell.ui.components.TabTopBar
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
    // v0.216.0（C6）：批量清空确认态——此前一点就删全部已完成文件，无确认
    var showClearDone by remember { mutableStateOf(false) }
    // v0.63.0：自动清理设置
    var showSettings by remember { mutableStateOf(false) }
    var autoDeleteWatched by remember { mutableStateOf(DownloadSettings.autoDeleteWatched(context)) }
    var autoClearDays by remember { mutableStateOf(DownloadSettings.autoClearDays(context)) }
    var lowSpaceWarn by remember { mutableStateOf(DownloadSettings.lowSpaceWarn(context)) }

    // v0.186.0 M19：进入下载页时对 DONE 做一次存在性扫描（唯一扫描点，启动时不重复做）；
    // 文件已丢的任务降级为失败态，卡片上直接显示原因，不静默
    LaunchedEffect(Unit) {
        val missing = DownloadStore.verifyDoneFiles(context)
        if (missing > 0) {
            AppToast.show(context, "$missing 个已完成任务的本地文件已丢失，已标为失败", long = true)
        }
    }

    // v0.247.0（F42）：同 F41，回到下载页时重读自动清理设置——
    // 别的入口（如备份恢复）改写后不再显示旧值。
    val dlLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(dlLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autoDeleteWatched = DownloadSettings.autoDeleteWatched(context)
                autoClearDays = DownloadSettings.autoClearDays(context)
                lowSpaceWarn = DownloadSettings.lowSpaceWarn(context)
            }
        }
        dlLifecycle.addObserver(observer)
        onDispose { dlLifecycle.removeObserver(observer) }
    }

    if (confirmDelete != null) {
        // 两个都是真实动作（删文件 / 仅移除记录），放 content 内双按钮，保留原行为
        TechDialog(
            onDismissRequest = { confirmDelete = null },
            title = "删除任务",
            dismissText = null,
        ) {
            Text("同时删除已下载的文件？", style = SoftType.Body)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "删除文件",
                    onClick = {
                        val t = confirmDelete!!
                        confirmDelete = null
                        scope.launch { DownloadManager.delete(context, t.id) }
                    },
                    variant = AppButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // v0.63.0：自动清理设置弹窗
    if (showSettings) {
        TechDialog(
            onDismissRequest = { showSettings = false },
            title = "自动清理",
            confirmText = "完成",
            onConfirm = { showSettings = false },
        ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("看完自动删除", style = SoftType.Body)
                            Text(
                                "本地文件播到 95% 以上时自动删",
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = autoDeleteWatched,
                            onCheckedChange = {
                                autoDeleteWatched = it
                                DownloadSettings.setAutoDeleteWatched(context, it)
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动清理已完成", style = SoftType.Body)
                            Text(
                                "启动时清理 N 天前完成的任务（含文件）",
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0 to "不清理", 3 to "3天", 7 to "7天", 14 to "14天", 30 to "30天").forEach { (days, label) ->
                            AppButton(
                                text = label,
                                onClick = {
                                    autoClearDays = days
                                    DownloadSettings.setAutoClearDays(context, days)
                                },
                                variant = if (autoClearDays == days) AppButtonVariant.Primary
                                else AppButtonVariant.Ghost,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("空间不足提醒", style = SoftType.Body)
                            Text(
                                "下载前剩余空间不足 500MB 时提醒",
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = lowSpaceWarn,
                            onCheckedChange = {
                                lowSpaceWarn = it
                                DownloadSettings.setLowSpaceWarn(context, it)
                            },
                        )
                    }
                }
        }
    }

    // v0.216.0（C6）：批量清空与单条删除同款确认，文案写明连文件一起删
    if (showClearDone) {
        TechDialog(
            onDismissRequest = { showClearDone = false },
            title = "清空已完成",
            confirmText = "清空",
            onConfirm = {
                showClearDone = false
                scope.launch { DownloadStore.clearDone(context) }
            },
        ) {
            Text(
                "将删除全部已完成任务的记录与已下载文件，此操作不可恢复。",
                style = SoftType.Body,
            )
        }
    }
    val active = tasks.filter { it.status != DlStatus.DONE }
    val done = tasks.filter { it.status == DlStatus.DONE }

    Scaffold(
        topBar = {
            TabTopBar(
                title = "下载管理",
                onBack = onBack,
                actions = {
                                if (done.isNotEmpty()) {
                                    AppButton(
                                        text = "清空已完成",
                                        onClick = { showClearDone = true },
                                        variant = AppButtonVariant.Ghost,
                                    )
                                }
                                // v0.63.0：自动清理设置
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "自动清理设置")
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
                                    AppToast.show(context, "已加入下载队列", long = false)
                                }
                            },
                            onDelete = { confirmDelete = task },
                            onPreview = {
                                val path = previewPath(context, task)
                                if (path != null) onPlayLocal(path, task.title)
                                else AppToast.show(context, "还没下到可播的部分", long = false)
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
                                else AppToast.show(context, "本地文件已丢失", long = false)
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
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = statusText(task),
                        fontSize = 11.sp,
                        color = if (task.status == DlStatus.FAILED) V4.c.live else V4.c.t3,
                    )
                }
                if (onPlay != null) {
                    IconButton(
                        onClick = onPlay,
                        modifier = Modifier.size(SoftDimens.TouchTarget),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = V4.c.t2)
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
                            tint = V4.c.t2,
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
                        tint = V4.c.t3,
                    )
                }
            }
            if (task.status != DlStatus.DONE) {
                LinearProgressIndicator(
                    progress = task.progress.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = V4.c.accent,
                    trackColor = V4.c.s3,
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
