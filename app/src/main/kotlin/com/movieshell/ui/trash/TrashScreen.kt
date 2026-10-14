package com.movieshell.ui.trash

import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(TrashStore.list(context)) }
    var showClearAll by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TrashItem?>(null) }

    fun reload() {
        items = TrashStore.list(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站", style = SoftType.Title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { showClearAll = true }) { Text("清空") }
                    }
                },
            )
        },
    ) { inner ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    kind = EmptyKind.Empty,
                    icon = Icons.Filled.DeleteForever,
                    title = "回收站是空的",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = SoftDimens.PagePadding),
        ) {
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
                                    Toast.makeText(context, "已恢复", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "恢复失败（文件已存在或已丢失）", Toast.LENGTH_LONG).show()
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

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("彻底删除") },
            text = { Text("彻底删除「${item.name}」吗？无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    TrashStore.deleteForever(context, item)
                    deleteTarget = null
                    reload()
                }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("清空回收站") },
            text = { Text("彻底删除回收站里的 ${items.size} 项吗？无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    TrashStore.clearAll(context)
                    showClearAll = false
                    reload()
                    Toast.makeText(context, "回收站已清空", Toast.LENGTH_SHORT).show()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text("取消") } },
        )
    }
}
