package com.movieshell.ui.favorites

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.FavoriteItem
import com.movieshell.data.repo.FavoriteStore
import com.movieshell.data.repo.Playlist
import com.movieshell.data.repo.PlaylistStore
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.GradientBlock
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 追剧收藏列表（v0.27.0 Soft Tech 换皮）：海报占位 + 标题 + 来源，点击进详情，右下角按钮删除。
 * v0.69.0：收藏片单——按片单分组展示，可新建/重命名/删除片单，条目可移动。
 *
 * 每次 ON_RESUME 都重读一次，保证从详情页收藏后切回来列表是最新的
 * （底栏 tab 用 saveState/restoreState，LaunchedEffect(Unit) 只跑一次不够）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onVodClick: (vodId: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var items by remember { mutableStateOf<List<FavoriteItem>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // v0.69.0：片单管理弹窗状态
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    // v0.69.0：条目移动目标
    var moveTarget by remember { mutableStateOf<FavoriteItem?>(null) }

    fun reload() {
        scope.launch {
            items = FavoriteStore.list(context)
            playlists = PlaylistStore.list(context)
            loading = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // v0.69.0：按片单分组（未分组放最后）
    val grouped = remember(items, playlists) {
        val byId = playlists.associateBy { it.id }
        val groups = LinkedHashMap<String?, List<FavoriteItem>>()
        playlists.forEach { p -> groups[p.id] = emptyList() }
        val ungrouped = mutableListOf<FavoriteItem>()
        items.forEach { item ->
            val pid = item.playlistId?.takeIf { it.isNotBlank() }
            if (pid != null && byId.containsKey(pid)) {
                groups[pid] = (groups[pid] ?: emptyList()) + item
            } else {
                ungrouped.add(item)
            }
        }
        if (ungrouped.isNotEmpty()) groups[null] = ungrouped
        groups
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收藏", style = SoftType.Title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                actions = {
                    // v0.69.0：新建片单
                    IconButton(onClick = { createName = ""; showCreate = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建片单")
                    }
                },
            )
        },
    ) { inner ->
        when {
            loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(SoftDimens.PagePadding),
                    verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                ) {
                    repeat(3) {
                        LoadingSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                        )
                    }
                }
            }
            items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Favorite,
                        title = "还没有收藏",
                        hint = "在详情页点右上角 ♥ 即可追剧",
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(vertical = 8.dp),
                ) {
                    grouped.forEach { (pid, groupItems) ->
                        if (groupItems.isEmpty()) return@forEach
                        val pl = pid?.let { id -> playlists.find { it.id == id } }
                        // v0.69.0：片单分组头
                        item(key = "header_$pid") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = SoftDimens.PagePadding,
                                        vertical = 6.dp,
                                    ),
                            ) {
                                Text(
                                    text = pl?.name ?: "未分组",
                                    style = SoftType.CardTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${groupItems.size}",
                                    style = SoftType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // 片单操作（未分组没有）
                                if (pl != null) {
                                    var menu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { menu = true }) {
                                            Icon(
                                                Icons.Filled.MoreVert,
                                                contentDescription = "片单操作",
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menu,
                                            onDismissRequest = { menu = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("重命名") },
                                                leadingIcon = {
                                                    Icon(Icons.Filled.Edit, null)
                                                },
                                                onClick = {
                                                    menu = false
                                                    renameName = pl.name
                                                    renameTarget = pl
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("删除片单") },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Filled.Delete,
                                                        null,
                                                        tint = MaterialTheme.colorScheme.error,
                                                    )
                                                },
                                                onClick = {
                                                    menu = false
                                                    deleteTarget = pl
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(
                            groupItems,
                            key = { "${it.sourceName}\u001f${it.vodId}" },
                        ) { item ->
                            FavoriteRow(
                                item = item,
                                onVodClick = onVodClick,
                                onDelete = {
                                    scope.launch {
                                        FavoriteStore.remove(
                                            context,
                                            item.vodId,
                                            item.sourceName,
                                        )
                                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                        reload()
                                    }
                                },
                                onMove = { moveTarget = item },
                            )
                        }
                    }
                }
            }
        }
    }

    // v0.69.0：新建片单弹窗
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建片单") },
            text = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    placeholder = { Text("片单名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val p = PlaylistStore.create(context, createName)
                        if (p == null) {
                            Toast.makeText(context, "名称为空或已存在", Toast.LENGTH_SHORT).show()
                        } else {
                            showCreate = false
                            reload()
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            },
        )
    }

    // v0.69.0：重命名弹窗
    renameTarget?.let { pl ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名片单") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (PlaylistStore.rename(context, pl.id, renameName)) {
                            renameTarget = null
                            reload()
                        } else {
                            Toast.makeText(context, "名称为空或已存在", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    // v0.69.0：删除片单确认
    deleteTarget?.let { pl ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除片单") },
            text = { Text("「${pl.name}」下的收藏会回到未分组，确定删除？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        PlaylistStore.delete(context, pl.id)
                        deleteTarget = null
                        reload()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // v0.69.0：移动条目到片单
    moveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("移动到片单") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            scope.launch {
                                FavoriteStore.setPlaylist(
                                    context, target.vodId, target.sourceName, null,
                                )
                                moveTarget = null
                                reload()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("未分组", modifier = Modifier.fillMaxWidth()) }
                    playlists.forEach { p ->
                        TextButton(
                            onClick = {
                                scope.launch {
                                    FavoriteStore.setPlaylist(
                                        context, target.vodId, target.sourceName, p.id,
                                    )
                                    moveTarget = null
                                    reload()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(p.name, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FavoriteRow(
    item: FavoriteItem,
    onVodClick: (vodId: String) -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    val context = LocalContext.current
    AppCard(
        onClick = {
            // v0.5.1：订阅站收藏回跳详情时恢复站点上下文
            DetailSiteHolder.site = item.subSiteKey
                .takeIf { it.isNotBlank() }
                ?.let { key ->
                    SubSiteStore.findSite(context, key)
                        ?.let { (subUrl, rec) -> SubSiteStore.toSite(subUrl, rec) }
                }
            onVodClick(item.vodId)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SoftDimens.PagePadding, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(SoftDimens.CardSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GradientBlock(
                key = item.vodId.ifBlank { item.name },
                modifier = Modifier
                    .size(56.dp, 76.dp)
                    .clip(RoundedCornerShape(SoftDimens.PosterCorner)),
            )
            Spacer(Modifier.width(SoftDimens.CardSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name.ifBlank { item.vodId },
                    style = SoftType.CardTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "来源：${item.sourceName.ifBlank { "未知" }}",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val date = formatSavedAt(item.savedAt)
                if (date.isNotBlank()) {
                    Text(
                        text = "收藏于 $date",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // v0.69.0：移动到片单
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("移动到片单") },
                        onClick = { menu = false; onMove() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除收藏") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

private fun formatSavedAt(ts: Long): String {
    if (ts <= 0) return ""
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }.getOrDefault("")
}
