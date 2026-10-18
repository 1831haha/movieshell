package com.movieshell.ui.follow

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.movieshell.data.download.Notify
import com.movieshell.data.repo.FollowItem
import com.movieshell.data.repo.FollowStore
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.PosterCard
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch

/**
 * 我的追剧（v0.26.0）：海报墙，按最近更新排序。
 *
 * 卡片显示"更新至 X 集" / "看到第 Y 集"；NEW 角标表示有未读更新（点进详情自动清）。
 * 长按取消追剧。
 *
 * v0.27.0 Soft Tech 换皮：卡片走公共 PosterCard（NEW 角标用其主色样式），
 * 空态走 EmptyState，对话框 20dp 圆角。逻辑与文案零变更。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FollowScreen(
    onBack: () -> Unit,
    onVodClick: (FollowItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Notify.RequestNotificationPermissionOnce()
    val items by FollowStore.list.collectAsState()
    var confirmUnfollow by remember { mutableStateOf<FollowItem?>(null) }

    if (confirmUnfollow != null) {
        AlertDialog(
            onDismissRequest = { confirmUnfollow = null },
            shape = RoundedCornerShape(SoftDimens.CardCorner),
            title = { Text("取消追剧", style = SoftType.CardTitle) },
            text = {
                Text("不再追《${confirmUnfollow!!.name}》？", style = SoftType.Body)
            },
            confirmButton = {
                AppButton(
                    text = "确定",
                    onClick = {
                        val item = confirmUnfollow!!
                        confirmUnfollow = null
                        scope.launch {
                            if (FollowStore.unfollow(context, item.id)) {
                                Toast.makeText(context, "已取消追剧", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            },
            dismissButton = {
                AppButton(
                    text = "取消",
                    onClick = { confirmUnfollow = null },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TabTopBar(
                title = "我的追剧（${items.size}）",
                onBack = onBack,
            )
        },
    ) { inner ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    kind = EmptyKind.Empty,
                    icon = Icons.Filled.PlaylistAddCheck,
                    title = "还没有追剧",
                    hint = "在详情页点“追剧”即可跟更",
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(SoftDimens.PagePadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
            ) {
                items(items, key = { it.id }) { item ->
                    FollowCard(
                        item = item,
                        onClick = { onVodClick(item) },
                        onLongClick = { confirmUnfollow = item },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FollowCard(
    item: FollowItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val subtitle = buildString {
        append("更新至 ${item.episodeCount} 集")
        if (item.watchedEpisode >= 0) {
            append(" · 看到第 ${item.watchedEpisode + 1} 集")
        }
    }
    Box(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        PosterCard(
            title = item.name,
            subtitle = subtitle,
            // v0.87.0：已完结也打角标（NEW 优先）
            badge = if (item.hasNew) "NEW" else if (item.finished) "已完结" else null,
            posterUrl = item.pic,
            modifier = Modifier.aspectRatio(0.55f),
        )
    }
}
