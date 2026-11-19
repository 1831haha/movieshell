package com.movieshell.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.source_mgr.ApiSource
import com.movieshell.data.store.ApiSourceStore
import com.movieshell.data.source_mgr.ClashProxy
import com.movieshell.data.source_mgr.DrpySourceManager
import com.movieshell.data.source_mgr.ServicePluginManager
import com.movieshell.data.source_mgr.SubSiteStore
import com.movieshell.data.store.RuleInfo
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.theme.V5
import kotlinx.coroutines.launch

/**
 * v1.3.26：Clash 代理设置页。
 *
 * 三块：① clash 服务状态（启动/停止/探活/去服务页）；② 订阅链接（只存本机，
 * 带 Clash UA 更新订阅，写进私有目录给 mihomo 的 file 型 provider 读）；
 * ③ 每源一个“走代理”开关（JS 规则 + CMS 订阅；PY/PHP/网页源 v1 不支持，
 * 见页内说明）。只代理开了开关的源，其他源一点不受影响。
 */
@Composable
fun ClashSettingsScreen(
    onBack: () -> Unit = {},
    onOpenServicePage: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf(ClashProxy.subscriptionUrl()) }
    var subStatus by remember {
        mutableStateOf(if (ClashProxy.hasSubscription()) "已拉取过订阅文件" else "还没拉过订阅")
    }
    var updating by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var jsRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    var cmsSubs by remember { mutableStateOf<List<ApiSource>>(emptyList()) }
    var proxyOn by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun refresh() {
        installed = ClashProxy.isInstalled(context)
        running = ClashProxy.isRunning()
        jsRules = runCatching { DrpySourceManager.listRules() }.getOrDefault(emptyList())
        cmsSubs = runCatching { ApiSourceStore.load(context) }.getOrDefault(emptyList())
        proxyOn = ClashProxy.enabledKeys()
    }
    LaunchedEffect(Unit) { refresh() }

    val statusText = when {
        !installed -> "未安装：去插件中心 → 服务页安装 clash 插件"
        running -> "运行中 · 控制端口 ${ClashProxy.controllerPort()} · 混合端口 ${ClashProxy.mixedPort()}"
        else -> "已安装，未运行"
    }

    Scaffold(
        topBar = { TabTopBar(title = "Clash 代理", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // ===== 服务状态 =====
            item { ClashCaption("服务") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SegItem(
                            position = SegPos.Single,
                            icon = Icons.Outlined.Extension,
                            title = "clash 服务",
                            subtitle = statusText,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (running) {
                                AppButton(
                                    text = "停止",
                                    onClick = {
                                        runCatching { ServicePluginManager.stop(context, ClashProxy.PLUGIN_ID) }
                                            .onFailure { AppToast.show(context, "停止失败：${it.message}") }
                                        refresh()
                                    },
                                    variant = AppButtonVariant.Secondary,
                                )
                            } else if (installed) {
                                AppButton(
                                    text = "启动",
                                    onClick = {
                                        runCatching { ServicePluginManager.start(context, ClashProxy.PLUGIN_ID) }
                                            .onFailure { AppToast.show(context, "启动失败：${it.message}") }
                                        refresh()
                                    },
                                )
                            }
                            AppButton(
                                text = "探活",
                                onClick = {
                                    scope.launch {
                                        val ok = runCatching {
                                            ServicePluginManager.healthCheck(context, ClashProxy.PLUGIN_ID)
                                        }.getOrDefault(false)
                                        AppToast.show(context, if (ok) "探活通过：服务可达" else "探活失败：服务不可达")
                                        refresh()
                                    }
                                },
                                variant = AppButtonVariant.Ghost,
                            )
                            AppButton(
                                text = "服务页",
                                onClick = onOpenServicePage,
                                variant = AppButtonVariant.Ghost,
                            )
                        }
                    }
                }
            }
            // ===== 订阅 =====
            item { ClashCaption("订阅") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TechTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = "粘贴 Clash 订阅链接",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(
                                text = "保存",
                                onClick = {
                                    if (ClashProxy.setSubscriptionUrl(urlInput)) {
                                        AppToast.show(context, "订阅链接已保存（只存本机）")
                                    } else {
                                        AppToast.show(context, "链接不对，要以 http:// 或 https:// 开头")
                                    }
                                },
                                enabled = urlInput.isNotBlank(),
                            )
                            AppButton(
                                text = if (updating) "更新中…" else "更新订阅",
                                onClick = {
                                    scope.launch {
                                        updating = true
                                        val r = ClashProxy.fetchSubscription()
                                        updating = false
                                        r.onSuccess { n ->
                                            subStatus = "已更新，节点约 $n 个"
                                            AppToast.show(context, "订阅已更新，节点约 $n 个")
                                        }.onFailure { e ->
                                            subStatus = e.message ?: "更新失败"
                                            AppToast.show(context, "更新失败：${e.message}")
                                        }
                                    }
                                },
                                variant = AppButtonVariant.Secondary,
                                enabled = !updating && ClashProxy.subscriptionUrl().isNotBlank(),
                            )
                        }
                        Text(
                            text = subStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "订阅只存本机私有目录，不上传；更新订阅时带 Clash 客户端标识拉取（面板只认它才回配置）。服务启动后自动读订阅里的节点，挑延迟最低的用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // ===== 按源走代理 =====
            item { ClashCaption("按源走代理") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "只代理开了开关的源，其他源一点不受影响。开了开关的源，搜索、封面、播放都走本地 clash 代理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        if (jsRules.isEmpty()) {
                            Text(
                                text = "暂无本地 JS 规则",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        } else {
                            jsRules.forEachIndexed { index, rule ->
                                val key = ClashProxy.jsKey(rule.name)
                                SegItem(
                                    position = clashPos(index, jsRules.size),
                                    icon = Icons.Outlined.Code,
                                    title = rule.title.ifBlank { rule.name },
                                    subtitle = rule.name,
                                    trailing = {
                                        AppSwitch(
                                            checked = proxyOn.contains(key),
                                            onCheckedChange = { on ->
                                                ClashProxy.setSourceProxied(key, on)
                                                if (on) {
                                                    DrpySourceManager.ruleHost(rule.name)?.let {
                                                        ClashProxy.registerHosts(key, listOf(it))
                                                    }
                                                }
                                                proxyOn = ClashProxy.enabledKeys()
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        if (cmsSubs.isEmpty()) {
                            Text(
                                text = "暂无 CMS 订阅",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        } else {
                            cmsSubs.forEachIndexed { index, sub ->
                                val key = ClashProxy.cmsKey(sub.url)
                                SegItem(
                                    position = clashPos(index, cmsSubs.size),
                                    icon = Icons.Outlined.Link,
                                    title = sub.name.ifBlank { sub.url },
                                    subtitle = sub.url,
                                    trailing = {
                                        AppSwitch(
                                            checked = proxyOn.contains(key),
                                            onCheckedChange = { on ->
                                                ClashProxy.setSourceProxied(key, on)
                                                if (on) {
                                                    // 订阅 URL 本身是订阅地址，不是站地址；
                                                    // 从缓存的站点 api 地址里取 host  prime。
                                                    val hosts = runCatching {
                                                        SubSiteStore.getCached(context, sub.url)?.sites
                                                            ?.mapNotNull { ClashProxy.hostOf(it.api) }
                                                            .orEmpty()
                                                    }.getOrDefault(emptyList())
                                                    if (hosts.isNotEmpty()) ClashProxy.registerHosts(key, hosts)
                                                }
                                                proxyOn = ClashProxy.enabledKeys()
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        Text(
                            text = "PY 规则走 Python 请求、PHP 走原生 php、网页源走浏览器，v1 暂不支持走代理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun clashPos(index: Int, size: Int): SegPos = when {
    size <= 1 -> SegPos.Single
    index == 0 -> SegPos.First
    index == size - 1 -> SegPos.Last
    else -> SegPos.Middle
}

@Composable
private fun ClashCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp),
    )
}
