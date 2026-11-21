package com.movieshell.ui.market

import com.movieshell.ui.theme.V5

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.core.log.LogBus
import com.movieshell.data.source_mgr.SubSiteStore
import com.movieshell.data.source.PluginMarket
import com.movieshell.data.store.PluginStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import kotlinx.coroutines.launch

/**
 * 插件详情页（v0.23.0）：图标/作者/版本/大小/标签/平台/完整描述/更新说明/安装按钮。
 * v0.27.0 换皮：字阶统一，空态走 EmptyState。逻辑与文案零变更。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(pluginId: String, market: String = "", onBack: () -> Unit = {}) {
    val context = LocalContext.current
    // v0.246.0：带市场精确查——导航传了点击时插件的 sourceMarket，多市场同 id 不串；
    // 市场为空时回落到旧的 cached(id)。
    val plugin = remember(pluginId, market) {
        if (market.isNotBlank()) PluginMarket.cached(pluginId, market) else PluginMarket.cached(pluginId)
    }
    var installedTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // v0.27.2：一键安装进度弹窗态
    var installProgress by remember { mutableStateOf<InstallProgressUi?>(null) }
    val installedVersion = remember(installedTick) {
        PluginStore.installedOf(pluginId)?.version
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        topBar = {
            TabTopBar(
                title = "插件详情",
                onBack = onBack,
            )
        },
    ) { inner ->
        if (plugin == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    kind = EmptyKind.Error,
                    icon = Icons.Filled.ErrorOutline,
                    title = "插件不存在",
                    hint = "请返回重进市场页",
                    actionText = "返回",
                    onAction = onBack,
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = V5.Gutter, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (plugin.iconUrl.isNotBlank()) {
                    AsyncImage(
                        model = plugin.iconUrl,
                        contentDescription = plugin.name,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(V5.CornerMd))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = plugin.name, style = MaterialTheme.typography.titleLarge)
                    if (plugin.author.isNotBlank()) {
                        Text(
                            text = "作者：${plugin.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "v${plugin.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (plugin.sourceMarket.isNotBlank()) {
                        Text(
                            text = "来自：${plugin.sourceMarket}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // v1.3.33：类型/分类/大小/平台/标签是纯展示信息，不做筛选跳转——
            // 原来 5 处 AssistChip(onClick = {}) 点按无任何反馈，改成不可点的
            // 静态标签（StaticTag），不要为“有动作”强行引入新筛选状态。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StaticTag(plugin.type)
                if (plugin.category.isNotBlank()) {
                    StaticTag(PluginUi.categoryLabel(plugin.category))
                }
                PluginMarket.formatSize(plugin.size).takeIf { it.isNotBlank() }?.let {
                    StaticTag(it)
                }
                if (plugin.platforms.isNotEmpty()) {
                    StaticTag(plugin.platforms.joinToString("/"))
                }
            }
            if (plugin.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    plugin.tags.forEach { tag ->
                        StaticTag(tag)
                    }
                }
            }
            if (plugin.desc.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(text = plugin.desc, style = MaterialTheme.typography.bodyMedium)
            }
            if (plugin.changelog.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(text = "更新说明", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = plugin.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            PluginActionButtons(
                plugin = plugin,
                installedVersion = installedVersion,
                busy = busy,
                onInstall = {
                    busy = true
                    // v0.27.4：长耗时任务走进程级 scope（根治 scope left the composition）
                    SubSiteStore.appScope.launch {
                        val (ok, msg) = PluginUi.installWithProgress(context, plugin) {
                            installProgress = it
                        }
                        PluginUi.toast(context.applicationContext, msg)
                        installProgress = null
                        installedTick++
                        busy = false
                        if (!ok) LogBus.e("PluginMarket", "安装失败已 toast：${plugin.id}")
                    }
                },
                onUninstall = {
                    busy = true
                    SubSiteStore.appScope.launch {
                        PluginUi.toast(
                            context.applicationContext,
                            PluginUi.uninstall(context, plugin),
                        )
                        installedTick++
                        busy = false
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            // v0.96.1：live 插件有备份时给"还原旧版"入口（一键拷回旧文件并刷新聚合）
            val hasBackup = remember(installedTick, installedVersion) {
                plugin.type == "live" && installedVersion != null &&
                    PluginMarket.hasLiveBackup(context, plugin.id)
            }
            if (hasBackup) {
                AppButton(
                    text = "还原旧版",
                    onClick = {
                        busy = true
                        SubSiteStore.appScope.launch {
                            val msg = runCatching {
                                PluginMarket.restoreLiveBackup(context, plugin.id)
                            }.fold(
                                onSuccess = { "已还原旧版：$it" },
                                onFailure = { "还原失败：${it.message}" },
                            )
                            PluginUi.toast(context.applicationContext, msg)
                            installedTick++
                            busy = false
                        }
                    },
                    enabled = !busy,
                    variant = AppButtonVariant.Secondary,
                )
                Spacer(Modifier.height(8.dp))
            }
            // v0.185.0：已安装的 setup 插件给“重新一键配置”入口（删了某个目录/
            // 说明文件时不用重装，点一下按插件里存的 docs 补齐缺的，已有不覆盖）
            val isSetupInstalled = plugin.type == "setup" && installedVersion != null
            if (isSetupInstalled) {
                AppButton(
                    text = "重新一键配置",
                    onClick = {
                        busy = true
                        SubSiteStore.appScope.launch {
                            val msg = runCatching {
                                PluginMarket.reconfigureSetup(context, plugin.id)
                            }.fold(
                                onSuccess = { it },
                                onFailure = { "重新配置失败：${it.message}" },
                            )
                            PluginUi.toast(context.applicationContext, msg)
                            installedTick++
                            busy = false
                        }
                    },
                    enabled = !busy,
                    variant = AppButtonVariant.Secondary,
                )
                Spacer(Modifier.height(8.dp))
            }
            val typeNote = when (plugin.type) {
                // v1.2.22：旧文案“Node.js 服务需自行运行”是错的——市场两家 server 插件
                // （danmu-service、hello-service）都是 .sh 脚本，由 App 用 /system/bin/sh
                // 在本机直接运行，只绑 127.0.0.1；只有 Node.js 写的服务才需用户自行运行。
                "server" -> "server 插件由 App 负责下载、校验并在本机运行（.sh 脚本服务），服务只绑 127.0.0.1；需 Node.js 的服务请自行运行"
                "env" -> "env 环境插件：依赖包解压到私有目录，PY 规则 import 即用；卸载后需重启 App 生效"
                "setup" -> "setup 一键配置插件：安装即在文件管理器建好目录并写入说明文档，已有文件不覆盖；卸载不删已建好的目录和说明"
                else -> ""
            }
            if (typeNote.isNotBlank()) {
                Text(
                    text = typeNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // v0.27.2：一键安装进度弹窗
    InstallProgressDialog(installProgress)
}

/** v1.3.33：纯展示标签（不可点、无涟漪）——插件详情的类型/分类/大小/平台/标签用它，不用 AssistChip 冒充可操作控件。 */
@Composable
private fun StaticTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
