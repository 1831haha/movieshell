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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
    var loading by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    items = FavoriteStore.list(context)
                    loading = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    items(items, key = { "${it.sourceName}\u001f${it.vodId}" }) { item ->
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
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            FavoriteStore.remove(
                                                context,
                                                item.vodId,
                                                item.sourceName,
                                            )
                                            Toast.makeText(
                                                context,
                                                "已删除",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            items = FavoriteStore.list(context)
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除收藏",
                                    )
                                }
                            }
                        }
                    }
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
