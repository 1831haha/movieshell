package com.movieshell.ui.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.LogBus

/**
 * v0.15.0：实验室页面。
 *
 * - 插件管理：已安装插件列表（PY/drpy/spider）
 * - 插件添加入口：右下角 + 按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实验室") },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    LogBus.i("UI", "点击实验室添加插件")
                    // TODO: 插件添加入口（文件选择/URL 输入）
                }
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加插件")
            }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
        ) {
            item {
                Text(
                    text = "插件管理",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已安装的 PY / drpy / spider 插件将显示在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }
            // TODO: 插件列表
            item {
                Column {
                    Icon(
                        Icons.Filled.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "暂无插件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
