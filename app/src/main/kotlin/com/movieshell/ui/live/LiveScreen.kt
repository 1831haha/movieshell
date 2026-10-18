package com.movieshell.ui.live

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Search
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
import com.movieshell.player.PlayerScreen
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/** v0.79.0：虎牙总站入口频道（插件里 url 为 huya 协议 portal 地址的那单条），点它进直播间列表而非直接播放 */
fun isHuyaPortal(ch: LiveChannel): Boolean = ch.url == "huya://portal"

/** 直播 Tab：DsPlayer 式布局（v0.80.0 重做）——顶部 16:9 播放器 + 节目信息卡 + 三列（分组/频道/源） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(
    onFullscreenChannel: (LiveChannel) -> Unit,
    onManageClick: () -> Unit,
    onHuyaPortalClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels by LiveStore.channels.collectAsState()
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var detailChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var favOnly by remember { mutableStateOf(false) }
    // v0.79.0：顶部播放器当前频道；进场自动选中第一个
    var selected by remember { mutableStateOf<LiveChannel?>(null) }
    // v0.80.0：左侧分组选中态
    var selectedGroup by remember { mutableStateOf<String?>(null) }

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
    // 频道列表就绪后自动选中第一个（无选中时）
    LaunchedEffect(channels) {
        if (selected == null && channels.isNotEmpty()) {
            val first = channels.firstOrNull { !isHuyaPortal(it) } ?: channels.first()
            selected = first
        }
    }

    fun playChannel(ch: LiveChannel) {
        if (isHuyaPortal(ch)) {
            onHuyaPortalClick()
        } else {
            selected = ch
        }
    }

    // 虎牙总站入口单独拎出来，不进三列
    val huyaPortal = remember(channels) { channels.firstOrNull { isHuyaPortal(it) } }
    val normalChannels = remember(channels) { channels.filter { !isHuyaPortal(it) } }

    val filtered = remember(normalChannels, query, favOnly) {
        normalChannels.filter {
            (!favOnly || it.favorite) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }
    val groups = remember(filtered) {
        filtered.groupBy { it.group.ifBlank { "未分组" } }.toSortedMap()
    }
    val groupNames = remember(groups) { groups.keys.toList() }
    // 分组选中态：保留旧选（若仍在），否则取首个
    LaunchedEffect(groupNames) {
        if (selectedGroup !in groupNames) selectedGroup = groupNames.firstOrNull()
    }
    // v0.80.1：selectedGroup 初始为 null（LaunchedEffect 异步才赋值），TreeMap.get(null)
    // 会直接 NPE 闪退；首帧先兜底取首个分组
    val groupChannels = remember(groups, selectedGroup) {
        val g = selectedGroup ?: groups.keys.firstOrNull()
        if (g == null) emptyList() else groups[g].orEmpty()
    }
    // 同名多线路 = 源1..N（对标 DsPlayer 右列）
    val sources = remember(channels, selected) {
        val name = selected?.name
        if (name.isNullOrBlank()) emptyList()
        else channels.filter { it.name == name && !isHuyaPortal(it) }
    }

    // EPG：当前节目 + 下一档（节目信息卡用）
    var epg by remember { mutableStateOf<Pair<EpgFetcher.Program?, EpgFetcher.Program?>?>(null) }
    LaunchedEffect(selected) {
        val ch = selected
        epg = if (ch != null && ch.epgUrl.isNotBlank()) {
            EpgFetcher.nowAndNext(ch.epgUrl, ch.name)
        } else {
            null
        }
    }

    Scaffold(
        topBar = {
            TabTopBar(
                title = "直播",
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索频道")
                    }
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
            // 顶部 16:9 播放器
            val playing = selected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                if (playing != null && !isHuyaPortal(playing)) {
                    PlayerScreen(
                        url = playing.url,
                        title = playing.name,
                        isLive = true,
                        onBack = {},
                        embedded = true,
                        onFullscreen = { onFullscreenChannel(playing) },
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(color = Color.White)
                        } else {
                            Text(
                                "选择下方频道开始播放",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            // 节目信息卡：台标 + 正在播/下一档（对标 DsPlayer）
            ProgramInfoCard(channel = playing, epg = epg)
            // 搜索行（默认收起，点顶栏放大镜展开）
            if (showSearch) {
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    placeholder = "搜索频道",
                )
            }
            // 虎牙总站入口
            if (huyaPortal != null) {
                HuyaEntryCard(onClick = onHuyaPortalClick)
            }
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
            // 三列：分组 | 频道 | 源
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // 左列：分组
                LazyColumn(
                    modifier = Modifier
                        .width(96.dp)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(groupNames, key = { it }) { g ->
                        GroupButton(
                            name = g,
                            count = groups[g]?.size ?: 0,
                            selected = g == selectedGroup,
                            onClick = { selectedGroup = g },
                        )
                    }
                }
                // 中列：频道（序号 + 台标 + 名称）
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = groupChannels.withIndex().toList(),
                        key = { it.value.id },
                    ) { (idx, ch) ->
                        ChannelCell(
                            index = idx + 1,
                            channel = ch,
                            isPlaying = selected?.id == ch.id,
                            onClick = { playChannel(ch) },
                            onLongClick = { detailChannel = ch },
                            onFavorite = { LiveStore.toggleFavorite(context, ch.id) },
                        )
                    }
                }
                // 右列：源（同名多线路）
                LazyColumn(
                    modifier = Modifier
                        .width(64.dp)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(
                        items = sources.withIndex().toList(),
                        key = { it.value.id },
                    ) { (idx, ch) ->
                        SourceButton(
                            label = "源${idx + 1}",
                            selected = selected?.id == ch.id,
                            onClick = { playChannel(ch) },
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
            onPlay = { playChannel(ch); detailChannel = null },
            onFavorite = { LiveStore.toggleFavorite(context, ch.id) },
            onDelete = {
                detailChannel = null
                TrashStore.trashLiveManual(context, ch)
                LiveStore.removeManual(context, ch.id)
                scope.launch { LiveStore.refresh(context) }
                Toast.makeText(context, "已移入回收站", Toast.LENGTH_LONG).show()
            },
        )
    }
}

/**
 * v0.80.0：节目信息卡（对标 DsPlayer 播放器下方那张卡）。
 * 台标 + 正在播 {节目} {开始-结束} / 下一档 {时间} {节目}；无 EPG 时显示频道名。
 */
@Composable
private fun ProgramInfoCard(
    channel: LiveChannel?,
    epg: Pair<EpgFetcher.Program?, EpgFetcher.Program?>?,
) {
    val (now, next) = epg ?: (null to null)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 台标
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel?.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = channel?.logo,
                        contentDescription = channel?.name,
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
                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "正在播",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = now.title,
                            style = SoftType.CardTitle,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${timeFmt.format(Date(now.startMs))}-${timeFmt.format(Date(now.stopMs))}",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (next != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "下一档 ${timeFmt.format(Date(next.startMs))} ${next.title}",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = channel?.name ?: "暂无频道",
                        style = SoftType.CardTitle,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (channel?.epgUrl.isNullOrBlank()) "暂无节目单" else "节目单加载中…",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** v0.80.0：左列分组按钮（选中=主题色药丸）。 */
@Composable
private fun GroupButton(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 0) "$name" else name,
            style = SoftType.Body,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** v0.80.0：中列频道格（序号 + 台标 + 名称 + 直播角标 + 收藏星）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCell(
    index: Int,
    channel: LiveChannel,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val soft = LocalSoftExtra.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else LocalSoftExtra.current.glass,
            )
            .border(
                SoftDimens.BorderWidth,
                if (isPlaying) MaterialTheme.colorScheme.primary else soft.border,
                shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$index",
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
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
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = SoftType.CardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
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
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(soft.errorSoft.copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("直播", color = soft.errorSoft, style = SoftType.Caption)
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) {
            Icon(
                if (channel.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (channel.favorite) "取消收藏" else "收藏",
                tint = if (channel.favorite) soft.warning
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** v0.80.0：右列源按钮（源1/源2…，选中=主题色）。 */
@Composable
private fun SourceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = SoftType.Body,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** v0.80.0：虎牙总站入口条（有 portal 频道时显示在三列上方）。 */
@Composable
private fun HuyaEntryCard(onClick: () -> Unit) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "虎牙直播",
                style = SoftType.CardTitle,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "进总站选台 >",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            TabTopBar(
                title = "直播订阅管理",
                onBack = onBack,
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
