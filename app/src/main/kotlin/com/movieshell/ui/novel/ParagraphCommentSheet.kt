package com.movieshell.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.domain.model.ParagraphComment
import com.movieshell.ui.components.sheetListScrollGuard

/**
 * v1.1.10：结构化段评——一段的评论组。
 * displayCount 是入口与组头显示的条数：源给出的段总数 total 大于 0 时用它，
 * 否则用本段已加载的条数（total 为 0 表示源不知道总数）。
 */
data class ParagraphCommentGroup(
    val paraIndex: Int,
    val comments: List<ParagraphComment>,
    val displayCount: Int,
)

/** 按段分组（按 paraIndex 升序）；空列表返回空组，不抛错。 */
fun groupParagraphComments(comments: List<ParagraphComment>): List<ParagraphCommentGroup> {
    if (comments.isEmpty()) return emptyList()
    return comments.groupBy { it.paraIndex }
        .entries.sortedBy { it.key }
        .map { (paraIndex, list) ->
            val total = list.firstOrNull()?.total ?: 0
            ParagraphCommentGroup(
                paraIndex = paraIndex,
                comments = list,
                displayCount = if (total > 0) total else list.size,
            )
        }
}

/** 本章评论总数：各段 displayCount 求和。 */
fun chapterParagraphCommentTotal(comments: List<ParagraphComment>): Int =
    groupParagraphComments(comments).sumOf { it.displayCount }

/**
 * v1.1.10：结构化段评底弹（只看不写，不加发表、回复、点赞按钮）。
 * 按段分组，组头点开收起；initialParaIndex 非空时默认只展开该段，
 * 为空时默认展开第一段。调用方包一层阅读主题的 MaterialTheme，
 * 这里只用 MaterialTheme.colorScheme，不自造颜色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParagraphCommentSheet(
    chapterTitle: String,
    comments: List<ParagraphComment>,
    loading: Boolean,
    initialParaIndex: Int?,
    onDismiss: () -> Unit,
) {
    val groups = remember(comments) { groupParagraphComments(comments) }
    var expanded by remember(groups, initialParaIndex) {
        mutableStateOf(
            when {
                initialParaIndex != null && groups.any { it.paraIndex == initialParaIndex } ->
                    setOf(initialParaIndex)
                groups.isNotEmpty() -> setOf(groups.first().paraIndex)
                else -> emptySet()
            },
        )
    }
    val listState = rememberLazyListState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .size(36.dp, 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
            )
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Text("本章段评", style = MaterialTheme.typography.titleMedium)
            if (chapterTitle.isNotBlank()) {
                Text(
                    chapterTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            when {
                loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text(
                            "加载中",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                groups.isEmpty() -> {
                    Text(
                        "本章暂无段评",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp)
                            .sheetListScrollGuard { listState.canScrollBackward },
                        state = listState,
                    ) {
                        items(groups, key = { it.paraIndex }) { group ->
                            val isOpen = expanded.contains(group.paraIndex)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = if (isOpen) expanded - group.paraIndex
                                            else expanded + group.paraIndex
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    // v1.1.10：paraIndex 数据层已是 1 起始，直接显示，不再加 1。
                                    "第 ${group.paraIndex} 段 · ${group.displayCount} 条",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (isOpen) "收起" else "展开",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isOpen) {
                                group.comments.forEach { c ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 4.dp, bottom = 12.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                c.author.ifBlank { "匿名" },
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                            )
                                            if (c.time.isNotBlank()) {
                                                Text(
                                                    c.time,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                        if (c.text.isNotBlank()) {
                                            Text(
                                                c.text,
                                                fontSize = 14.sp,
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                        }
                                        if (c.likes > 0) {
                                            Text(
                                                "赞 ${c.likes}",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 2.dp),
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
    }
}
