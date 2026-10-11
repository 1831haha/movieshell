package com.movieshell.player

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "填充",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪",
)

/** 断点续播阈值：上次进度超过 10 秒才续播 */
private const val RESUME_THRESHOLD_MS = 10_000L

/**
 * 全屏播放器界面。
 *
 * @param url 当前播放地址（变化时自动切集并保存旧进度）
 * @param title 标题栏显示的标题
 * @param episodes 选集列表（为空则不显示选集按钮）
 * @param currentEpisode 当前集标识，用于在选集中高亮
 * @param onEpisodeClick 点击选集回调（调用方负责切 url）
 * @param onBack 返回回调
 */
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    episodes: List<String> = emptyList(),
    currentEpisode: String? = null,
    onEpisodeClick: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var speedIndex by remember { mutableIntStateOf(1) } // 默认 1.0x
    var resizeIndex by remember { mutableIntStateOf(0) } // 默认适应
    var showEpisodes by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showServiceMissingDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = onBack)

    // DisposableEffect 的 onDispose 闭包捕获的是首次组合的值，
    // 用 rememberUpdatedState 保证退出时存的是最新的 url/title。
    val currentUrl by rememberUpdatedState(url)
    val currentTitle by rememberUpdatedState(title)

    // 沉浸式全屏 + 退出时保存进度
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            savePlayerProgress(context, currentTitle, currentUrl)
        }
    }

    // 播放 / 切集：先保存上一集进度，再播新地址（带断点续播）
    // url 为空表示暂无可播放地址（演示数据阶段），不启动播放器
    // magnet: 地址不直接播，走 MagnetPlayer 探测本机转播服务后再起播；
    // 历史记录一律用原始 url（磁力链接本身）做 key，保证磁力也能断点续播
    var lastUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        lastUrl?.let { prev -> savePlayerProgress(context, title, prev) }
        if (url.isNotBlank()) {
            val resumeAt = HistoryStore.getProgress(context, url)
                .takeIf { it > RESUME_THRESHOLD_MS } ?: 0L
            val started = if (MagnetPlayer.isMagnet(url)) {
                MagnetPlayer.playIfReady(
                    context = context,
                    magnet = url,
                    title = title,
                    startPositionMs = resumeAt,
                )
            } else {
                PlayerManager.init(context)
                PlayerManager.play(url, startPositionMs = resumeAt)
                true
            }
            showServiceMissingDialog = !started
        }
        lastUrl = url
    }

    // 播放错误 -> Snackbar 提示
    DisposableEffect(Unit) {
        PlayerManager.setOnErrorListener { e ->
            errorMsg = e.message ?: "播放出错"
        }
        onDispose { PlayerManager.setOnErrorListener(null) }
    }
    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            snackbarHostState.showSnackbar(it)
            errorMsg = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (url.isBlank()) {
            Text(
                text = "暂无可播放地址",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }.also {
                        it.player = PlayerManager.player
                        playerView = it
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 顶部栏：返回 + 标题 + 倍速 + 画面比例 + 选集
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    speedIndex = (speedIndex + 1) % SPEEDS.size
                    PlayerManager.player?.setPlaybackSpeed(SPEEDS[speedIndex])
                }
            ) {
                Text("${SPEEDS[speedIndex]}x", color = Color.White)
            }
            TextButton(
                onClick = {
                    resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
                    playerView?.resizeMode = RESIZE_MODES[resizeIndex].first
                }
            ) {
                Text(RESIZE_MODES[resizeIndex].second, color = Color.White)
            }
            if (episodes.isNotEmpty()) {
                IconButton(onClick = { showEpisodes = true }) {
                    Icon(Icons.Filled.PlaylistPlay, contentDescription = "选集", tint = Color.White)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )

        if (showEpisodes) {
            EpisodeSheet(
                episodes = episodes,
                currentEpisode = currentEpisode,
                onDismiss = { showEpisodes = false },
                onEpisodeClick = {
                    showEpisodes = false
                    onEpisodeClick(it)
                },
            )
        }

        // 磁力转播服务未启动提示
        if (showServiceMissingDialog) {
            AlertDialog(
                onDismissRequest = { showServiceMissingDialog = false },
                title = { Text("磁力转播服务未启动") },
                text = {
                    Text("未检测到本机磁力转播服务（127.0.0.1:17890），请先在 DsPlayer 的服务页启动 magnet-stream 后再试")
                },
                confirmButton = {
                    TextButton(onClick = { showServiceMissingDialog = false }) {
                        Text("知道了")
                    }
                },
            )
        }
    }
}

/** 读取当前进度并写入历史记录 */
private fun savePlayerProgress(context: Context, title: String, url: String) {
    val player = PlayerManager.player ?: return
    val position = player.currentPosition
    if (position <= 0) return
    HistoryStore.saveProgress(
        context = context,
        title = title,
        url = url,
        positionMs = position,
        durationMs = player.duration.coerceAtLeast(0L),
    )
}

/** 底部弹出的选集抽屉 */
@Composable
private fun EpisodeSheet(
    episodes: List<String>,
    currentEpisode: String?,
    onDismiss: () -> Unit,
    onEpisodeClick: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 半透明遮罩，点击关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss)
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "选集（${episodes.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(episodes) { _, episode ->
                        val selected = episode == currentEpisode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEpisodeClick(episode) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Text(
                                text = episode,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
