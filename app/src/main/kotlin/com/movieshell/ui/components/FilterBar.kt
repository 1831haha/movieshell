package com.movieshell.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 全量重做新 UI：筛选条（DaoBox filter_bar 词汇的 Compose 版）。
 *
 * 横向滚动的分类 chip 行：首页分类行原来是内联 Row + V4Chip，
 * 收成共享组件后分类各屏同调一处。行为与原来逐字一致：
 * 8.dp 间距、选中态走 V4Chip；selectedIndex 为 -1 时都不选中
 *（首页在“推荐”时就是这个状态）。
 */
@Composable
fun FilterBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            V4Chip(
                text = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}
