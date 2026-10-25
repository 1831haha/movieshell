package com.movieshell.ui.trash

import androidx.compose.foundation.layout.Arrangement
import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.TrashItem
import com.movieshell.data.repo.TrashStore
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun kindLabel(kind: String): String = when (kind) {
    "js" -> "JS 规则"
    "py" -> "PY 规则"
    "php" -> "PHP 规则"
    "web" -> "网页源"
    "sub" -> "订阅"
    "live-manual" -> "直播频道"
    else -> kind
}

/**
 * 回收站（v0.24.0；v0.27.0 Soft Tech 换皮）：软删除的源/文件列表，可恢复、彻底删除、清空。
 * v0.86.0：embedded=true 时不画自己的 Scaffold 顶栏，只出内容（站源页 tab 内嵌用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit = {},
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(TrashStore.list(context)) }
    var showClearAll by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TrashItem?>(null) }

    fun reload() {
        items = TrashStore.list(context)
    }

    @Composable
    fun TrashContent(modifier: Modifier = Modifier) {
        if (items.isEmpty()) {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    kind = EmptyKind.Empty,
                    icon = Icons.Filled.DeleteForever,
                    title = "回收站是空的",
                )
            }
            return
        }
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = SoftDimens.PagePadding),
        ) {
            // embedded 模式没有顶栏，清空按钮放这里
            if (embedded) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showClearAll = true }) { Text("清空") }
                    }
                }
            }
            item {
                Text(
                    "删除的源和规则文件会先放这里，可恢复；彻底删除后无法找回",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(items, key = { it.id }) { item ->
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(SoftDimens.CardSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = SoftType.CardTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${kindLabel(item.kind)} · 删除于 ${timeFmt.format(Date(item.deletedAt))}",
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AppButton(
                            text = "恢复",
                            variant = AppButtonVariant.Ghost,
                            onClick = {
                                if (TrashStore.restore(context, item)) {
                                    AppToast.show(context, "已恢复", long = false)
                                } else {
                                    AppToast.show(context, "恢复失败（文件已存在或已丢失）", long = true)
                                }
                                reload()
                            },
                        )
                        IconButton(onClick = { deleteTarget = item }) {
                            Icon(
                                Icons.Filled.DeleteForever, contentDescription = "彻底删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (embedded) {
        TrashContent()
    } else {
        Scaffold(
            topBar = {
                TabTopBar(
                    title = "回收站",
                    onBack = onBack,
                    actions = {
                        if (items.isNotEmpty()) {
                            TextButton(onClick = { showClearAll = true }) { Text("清空") }
                        }
                    },
                )
            },
        ) { inner ->
            TrashContent(Modifier.padding(inner))
        }
    }

    deleteTarget?.let { item ->
        TechDialog(
            onDismissRequest = { deleteTarget = null },
            title = "彻底删除",
            confirmText = "彻底删除",
            onConfirm = {
                TrashStore.deleteForever(context, item)
                deleteTarget = null
                reload()
            },
        ) {
            Text("彻底删除「${item.name}」吗？无法恢复。")
        }
    }

    if (showClearAll) {
        TechDialog(
            onDismissRequest = { showClearAll = false },
            title = "清空回收站",
            confirmText = "清空",
            onConfirm = {
                TrashStore.clearAll(context)
                showClearAll = false
                reload()
                AppToast.show(context, "回收站已清空", long = false)
            },
        ) {
            Text("彻底删除回收站里的 ${items.size} 项吗？无法恢复。")
        }
    }
}
