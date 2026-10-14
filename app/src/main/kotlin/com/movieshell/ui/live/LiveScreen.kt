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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                title = { Text("直播") },
                actions = {
                    IconButton(onClick = { favOnly = !favOnly }) {
                        Icon(
                            if (favOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "只看收藏",
                            tint = if (favOnly) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索频道") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Tv, contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("还没有直播频道", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点右上角设置添加 M3U 订阅，或从插件市场安装 live 插件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                groups.forEach { (group, list) ->
                    item(key = "g:$group") {
                        Text(
                            text = "$group（${list.size}）",
                            style = MaterialTheme.typography.labelLarge,
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.group.isNotBlank()) {
                    Text(
                        text = channel.group,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 直播角标
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE53935))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("直播", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (channel.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (channel.favorite) "取消收藏" else "收藏",
                    tint = if (channel.favorite) Color(0xFFFFB300)
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                    Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        (channel.group.ifBlank { "未分组" }) + " · " +
                            when {
                                channel.sourceKey.startsWith("manual") -> "手动添加"
                                channel.sourceKey.startsWith("m3u:") -> "订阅：" + channel.sourceKey.removePrefix("m3u:")
                                else -> "插件源"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (channel.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "收藏",
                        tint = if (channel.favorite) Color(0xFFFFB300)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // EPG：当前 + 后续（尽力展示）
            if (channel.epgUrl.isNotBlank()) {
                Text("节目单", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                when {
                    epgLoading -> Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    epg == null || (epg?.first == null && epg?.second == null) ->
                        Text("暂无节目数据", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        epg?.first?.let { p ->
                            Text(
                                "正在播：${p.title}（${timeFmt.format(Date(p.startMs))}–${timeFmt.format(Date(p.stopMs))}）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        epg?.second?.let { p ->
                            Text(
                                "接下来：${p.title}（${timeFmt.format(Date(p.startMs))}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) { Text("播放") }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(channel.url))
                    Toast.makeText(context, "播放地址已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制地址") }
                if (channel.manual) {
                    OutlinedButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
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
                title = { Text("直播订阅管理") },
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
                    Button(onClick = { showAddSub = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加订阅")
                    }
                    OutlinedButton(
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier.weight(1f),
                    ) { Text("导入本地 M3U") }
                    OutlinedButton(
                        onClick = { showAddChannel = true },
                        modifier = Modifier.weight(1f),
                    ) { Text("手动加频道") }
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
                    Text(
                        "暂无订阅，可添加 M3U 订阅地址（如公开 IPTV 源）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(sources, key = { it.url }) { s ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                if (s.type == LiveSource.LOCAL_FILE) "本地文件" else s.url,
                                style = MaterialTheme.typography.bodySmall,
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
            title = { Text("添加 M3U 订阅") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("名称") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; err = false },
                        label = { Text("订阅地址") },
                        placeholder = { Text("https://…/xxx.m3u") },
                        singleLine = true, isError = err,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = if (err) {
                            { Text("请输入 http(s) 开头的地址") }
                        } else null,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val u = url.trim()
                    if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                        err = true
                        return@Button
                    }
                    if (LiveStore.addSource(context, name.ifBlank { "M3U订阅" }, LiveSource.M3U_URL, u)) {
                        showAddSub = false
                        scope.launch { LiveStore.refresh(context) }
                    } else {
                        Toast.makeText(context, "该订阅已存在", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showAddSub = false }) { Text("取消") } },
        )
    }

    if (showAddChannel) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var logo by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddChannel = false },
            title = { Text("手动添加频道") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("频道名") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; err = false },
                        label = { Text("播放地址") },
                        placeholder = { Text("https://…/xxx.m3u8") },
                        singleLine = true, isError = err,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = if (err) {
                            { Text("请输入 http(s) 开头的地址") }
                        } else null,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = logo, onValueChange = { logo = it },
                        label = { Text("台标地址（可选）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val u = url.trim()
                    if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                        err = true
                        return@Button
                    }
                    if (LiveStore.addManual(context, name.ifBlank { "未命名频道" }, u, logo)) {
                        showAddChannel = false
                        scope.launch { LiveStore.refresh(context) }
                    } else {
                        Toast.makeText(context, "该频道已存在", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showAddChannel = false }) { Text("取消") } },
        )
    }
}
