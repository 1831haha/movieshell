package com.movieshell.ui.changelog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.theme.SoftType

/**
 * 更新日志独立页（v0.30.0）。
 *
 * 从手册里独立出来：设置 → 更新日志，按版本倒序，每版可展开看详细更新内容。
 * 数据为静态 [CHANGELOG]，不走网络。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TabTopBar(
                title = "更新日志",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            items(CHANGELOG, key = { it.version }) { entry ->
                ChangelogItem(entry)
                Spacer(Modifier.height(8.dp))
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ChangelogItem(entry: ChangelogEntry) {
    var expanded by remember { mutableStateOf(entry.version == CHANGELOG.first().version) }
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.version,
                        style = SoftType.Title,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = entry.title,
                        style = SoftType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.date.isNotBlank()) {
                        Text(
                            text = entry.date,
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    entry.details.forEach { line ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(
                                text = "· ",
                                style = SoftType.Body,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = line,
                                style = SoftType.Body,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
