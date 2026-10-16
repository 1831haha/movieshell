package com.movieshell.ui.live

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.EpgFetcher
import com.movieshell.data.repo.LiveChannel
import com.movieshell.data.repo.LiveSource
import com.movieshell.data.repo.LiveStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.TrashStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/** 直播 Tab：频道列表（按分组）+ 搜索 + 收藏 + 订阅管理 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(
    onPlayChannel: (LiveChannel) -> Unit,
    onManageClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels by LiveStore.channels.collectAsState()
    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var detailChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var favOnly by remember { mutableStateOf(false) }

    fun doRefresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            runCatching { LiveStore.refresh(context) }
                .onFailure { Toast.makeText(context, "刷新失败：${it.message}", Toast.LENGTH_LONG).show() }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        if (channels.isEmpty()) doRefresh()
    }

    val filtered = remember(channels, query, favOnly) {
        channels.filter {
            (!favOnly || it.favorite) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }
    val groups = remember(filtered) {
        filtered.groupBy { it.group.ifBlank { "未分组" } }.toSortedMap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // v0.39.0：mockup 风格香槟金标题
                title = { Text("直播", style = SoftType.Title, color = Color(0xFFD9C39A)) },
                actions = {
                    IconButton(onClick = { favOnly = !favOnly }) {
                        Icon(
                            if (favOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "只看收藏",
                            tint = if (favOnly) LocalSoftExtra.current.warning
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { doRefresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onManageClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "订阅管理")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            AppTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = "搜索频道",
            )
            if (refreshing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在拉取直播源…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (filtered.isEmpty() && !refreshing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Tv,
                        title = "还没有直播频道",
                        hint = "点右上角设置添加 M3U 订阅，或从插件市场安装 live 插件",
                    )
                }
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                groups.forEach { (group, list) ->
                    item(key = "g:$group") {
                        Text(
                            text = "$group（${list.size}）",
                            style = SoftType.CardTitle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(list, key = { it.id }) { ch ->
                        ChannelRow(
                            channel = ch,
                            onClick = { onPlayChannel(ch) },
                            onLongClick = { detailChannel = ch },
                            onFavorite = {
                                LiveStore.toggleFavorite(context, ch.id)
                            },
                        )
                    }
                }
            }
        }
    }

    // 频道详情（长按）：台标/信息/EPG 当前+后续/收藏/删除
    detailChannel?.let { ch ->
        ChannelDetailSheet(
            channel = ch,
            onDismiss = { detailChannel = null },
            onPlay = { onPlayChannel(ch); detailChannel = null },
            onFavorite = { LiveStore.toggleFavorite(context, ch.id) },
            onDelete = {
                detailChannel = null
                TrashStore.trashLiveManual(context, ch)
                LiveStore.removeManual(context, ch.id)
                scope.launch { LiveStore.refresh(context) }
                Toast.makeText(context, "已移入回收站", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: LiveChannel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val soft = LocalSoftExtra.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 台标
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo.isNotBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        Icons.Filled.Tv, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = SoftType.CardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.group.isNotBlank()) {
                    Text(
                        text = channel.group,
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 直播角标
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(soft.errorSoft.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("直播", color = soft.errorSoft, style = SoftType.Caption)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (channel.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (channel.favorite) "取消收藏" else "收藏",
                    tint = if (channel.favorite) soft.warning
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDetailSheet(
    channel: LiveChannel,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var epg by remember(channel.id) { mutableStateOf<Pair<EpgFetcher.Program?, EpgFetcher.Program?>?>(null) }
    var epgLoading by remember(channel.id) { mutableStateOf(false) }

    LaunchedEffect(channel.id) {
        if (channel.epgUrl.isNotBlank()) {
            epgLoading = true
            epg = EpgFetcher.nowAndNext(channel.epgUrl, channel.name)
            epgLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            topStart = SoftDimens.CardCorner,
            topEnd = SoftDimens.CardCorner,
        ),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (channel.logo.isNotBlank()) {
                        AsyncImage(
                            model = channel.logo,
                            contentDescription = channel.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(Icons.Filled.Tv, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        channel.name,
                        style = SoftType.CardTitle,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        (channel.group.ifBlank { "未分组" }) + " · " +
                            when {
                                channel.sourceKey.startsWith("manual") -> "手动添加"
                                channel.sourceKey.startsWith("m3u:") -> "订阅：" + channel.sourceKey.removePrefix("m3u:")
                                else -> "插件源"
                            },
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (channel.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "收藏",
                        tint = if (channel.favorite) LocalSoftExtra.current.warning
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // EPG：当前 + 后续（尽力展示）
            if (channel.epgUrl.isNotBlank()) {
                Text(
                    "节目单",
                    style = SoftType.CardTitle,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                when {
                    epgLoading -> Text("加载中…", style = SoftType.Caption)
                    epg == null || (epg?.first == null && epg?.second == null) ->
                        Text("暂无节目数据", style = SoftType.Caption)
                    else -> {
                        epg?.first?.let { p ->
                            Text(
                                "正在播：${p.title}（${timeFmt.format(Date(p.startMs))}–${timeFmt.format(Date(p.stopMs))}）",
                                style = SoftType.Body,
                            )
                        }
                        epg?.second?.let { p ->
                            Text(
                                "接下来：${p.title}（${timeFmt.format(Date(p.startMs))}）",
                                style = SoftType.Body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = "播放",
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.Primary,
                )
                AppButton(
                    text = "复制地址",
                    onClick = {
                        clipboard.setText(AnnotatedString(channel.url))
                        Toast.makeText(context, "播放地址已复制", Toast.LENGTH_SHORT).show()
                    },
                    variant = AppButtonVariant.Secondary,
                )
                if (channel.manual) {
                    AppButton(
                        text = "删除",
                        onClick = onDelete,
                        variant = AppButtonVariant.Ghost,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 读 content Uri 的显示文件名，失败返回 null */
private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? {
    resolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
    }
    return null
}

/** 直播订阅管理：M3U 订阅 URL / 本地 M3U 导入 / 手动添加频道 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveManageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by LiveStore.sources.collectAsState()
    var showAddSub by remember { mutableStateOf(false) }
    var showAddChannel by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            refreshing = true
            val ok = try {
                val name = displayNameOf(context.contentResolver, uri)
                    ?.substringBeforeLast('.')?.ifBlank { null } ?: "本地M3U"
                val tmp = File(context.cacheDir, "m3u_import_${System.currentTimeMillis()}.m3u")
                val ins = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取文件")
                ins.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val done = LiveStore.importLocalFile(context, tmp, name)
                tmp.delete()
                done
            } catch (e: Exception) {
                LogBus.e("Live", "导入本地 M3U 失败: ${e.message}")
                false
            }
            Toast.makeText(
                context,
                if (ok) "已导入本地 M3U" else "导入失败",
                Toast.LENGTH_SHORT,
            ).show()
            refreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("直播订阅管理", style = SoftType.Title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppButton(
                        text = "添加订阅",
                        onClick = { showAddSub = true },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Primary,
                    )
                    AppButton(
                        text = "导入本地 M3U",
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Secondary,
                    )
                    AppButton(
                        text = "手动加频道",
                        onClick = { showAddChannel = true },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Secondary,
                    )
                }
                if (refreshing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                }
            }
            if (sources.isEmpty()) {
                item {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Tv,
                        title = "暂无订阅",
                        hint = "可添加 M3U 订阅地址（如公开 IPTV 源）",
                    )
                }
            }
            items(sources, key = { it.url }) { s ->
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = SoftType.CardTitle,
                            )
                            Text(
                                if (s.type == LiveSource.LOCAL_FILE) "本地文件" else s.url,
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            LiveStore.removeSource(context, s.url)
                            scope.launch { LiveStore.refresh(context) }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除订阅")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showAddSub) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddSub = false },
            shape = RoundedCornerShape(SoftDimens.CardCorner),
            title = { Text("添加 M3U 订阅", style = SoftType.CardTitle) },
            text = {
                Column {
                    Text(
                        text = "名称",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "订阅地址",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = url,
                        onValueChange = { url = it; err = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "https://…/xxx.m3u",
                    )
                    if (err) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "请输入 http(s) 开头的地址",
                            style = SoftType.Caption,
                            color = LocalSoftExtra.current.errorSoft,
                        )
                    }
                }
            },
            confirmButton = {
                AppButton(
                    text = "确定",
                    onClick = {
                        val u = url.trim()
                        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                            err = true
                            return@AppButton
                        }
                        if (LiveStore.addSource(context, name.ifBlank { "M3U订阅" }, LiveSource.M3U_URL, u)) {
                            showAddSub = false
                            scope.launch { LiveStore.refresh(context) }
                        } else {
                            Toast.makeText(context, "该订阅已存在", Toast.LENGTH_SHORT).show()
                        }
                    },
                    variant = AppButtonVariant.Primary,
                )
            },
            dismissButton = {
                AppButton(
                    text = "取消",
                    onClick = { showAddSub = false },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }

    if (showAddChannel) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var logo by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddChannel = false },
            shape = RoundedCornerShape(SoftDimens.CardCorner),
            title = { Text("手动添加频道", style = SoftType.CardTitle) },
            text = {
                Column {
                    Text(
                        text = "频道名",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "播放地址",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = url,
                        onValueChange = { url = it; err = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "https://…/xxx.m3u8",
                    )
                    if (err) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "请输入 http(s) 开头的地址",
                            style = SoftType.Caption,
                            color = LocalSoftExtra.current.errorSoft,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "台标地址（可选）",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = logo, onValueChange = { logo = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                AppButton(
                    text = "确定",
                    onClick = {
                        val u = url.trim()
                        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                            err = true
                            return@AppButton
                        }
                        if (LiveStore.addManual(context, name.ifBlank { "未命名频道" }, u, logo)) {
                            showAddChannel = false
                            scope.launch { LiveStore.refresh(context) }
                        } else {
                            Toast.makeText(context, "该频道已存在", Toast.LENGTH_SHORT).show()
                        }
                    },
                    variant = AppButtonVariant.Primary,
                )
            },
            dismissButton = {
                AppButton(
                    text = "取消",
                    onClick = { showAddChannel = false },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }
}
