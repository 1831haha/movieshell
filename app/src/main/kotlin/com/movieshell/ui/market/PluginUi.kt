package com.movieshell.ui.market

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PluginMarket
import com.movieshell.data.repo.PluginMarket.MarketPlugin
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant

/**
 * 插件市场共享 UI（v0.23.0）：安装/卸载动作单入口 + 按钮状态机，列表页与详情页共用。
 * v0.27.0 Soft Tech 换皮：按钮走 AppButton，状态机逻辑零变更。
 */
object PluginUi {

    /**
     * v0.27.2：带三段进度（下载→校验→安装）的安装，onState 驱动 InstallProgressDialog。
     * 返回 Pair(是否成功, toast 文案)；调用方负责 toast 并清掉进度态。
     */
    suspend fun installWithProgress(
        context: Context,
        p: MarketPlugin,
        onState: (InstallProgressUi?) -> Unit,
    ): Pair<Boolean, String> {
        onState(InstallProgressUi(p.name, "准备下载…", 0, p.size, true))
        return try {
            PluginMarket.install(context, p) { stage, done, total ->
                onState(
                    InstallProgressUi(
                        pluginName = p.name,
                        stage = when (stage) {
                            PluginMarket.InstallStage.Downloading -> "下载中"
                            PluginMarket.InstallStage.Verifying -> "校验中"
                            PluginMarket.InstallStage.Installing -> "安装中"
                        },
                        done = done,
                        total = total,
                        indeterminate = stage != PluginMarket.InstallStage.Downloading || total <= 0,
                    )
                )
            }
            true to "已安装 ${p.name} v${p.version}"
        } catch (e: Exception) {
            LogBus.e("PluginMarket", "安装失败：${e.message}（${p.id}）")
            false to "安装失败：${e.message}"
        }
    }

    /** 卸载：返回 toast 文案。 */
    suspend fun uninstall(context: Context, p: MarketPlugin): String = try {
        PluginMarket.uninstall(context, p.id)
        "已卸载 ${p.name}"
    } catch (e: Exception) {
        LogBus.e("PluginMarket", "卸载失败：${e.message}（${p.id}）")
        "卸载失败：${e.message}"
    }

    fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun categoryLabel(category: String): String = when (category) {
        "env" -> "环境"
        "app" -> "应用"
        else -> category
    }

    /** v0.27.2：安装进度条文案。 */
    fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }
}

/** v0.27.2：一键安装进度态（下载→校验→安装），null=不显示。 */
data class InstallProgressUi(
    val pluginName: String,
    val stage: String,
    val done: Long,
    val total: Long,
    val indeterminate: Boolean,
)

/**
 * v0.27.2：一键安装进度弹窗。下载阶段定量进度条（x%/MB），
 * 校验/安装阶段不定量转圈；安装中不可取消（关了会装一半）。
 */
@Composable
fun InstallProgressDialog(state: InstallProgressUi?) {
    if (state == null) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("正在安装 ${state.pluginName}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.stage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.indeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    val frac = (state.done.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = frac,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${PluginUi.formatBytes(state.done)} / ${PluginUi.formatBytes(state.total)}" +
                            "（${(frac * 100).toInt()}%）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

/**
 * 安装/更新/卸载按钮组。
 * - 不支持的 type：按钮置灰，提示原因。
 * - minApp 高于当前版本：安装/更新置灰，提示"需侠影 X 及以上"。
 */
@Composable
fun PluginActionButtons(
    plugin: MarketPlugin,
    installedVersion: String?,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val hasUpdate = installedVersion != null &&
        PluginMarket.isNewer(plugin.version, installedVersion)
    val blocked = !plugin.isSupported()
    val needApp = plugin.needsNewerApp()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            return@Row
        }
        when {
            blocked -> {
                AppButton(
                    text = "侠影暂不支持（${plugin.type}）",
                    onClick = {},
                    enabled = false,
                    variant = AppButtonVariant.Secondary,
                )
            }
            needApp -> {
                AppButton(
                    text = "需侠影 ${plugin.minApp} 及以上",
                    onClick = {},
                    enabled = false,
                    variant = AppButtonVariant.Secondary,
                )
                if (installedVersion != null) {
                    AppButton(
                        text = "卸载",
                        onClick = onUninstall,
                        variant = AppButtonVariant.Ghost,
                        icon = Icons.Filled.Delete,
                    )
                }
            }
            installedVersion == null -> {
                AppButton(
                    text = "安装",
                    onClick = onInstall,
                    icon = Icons.Filled.CloudDownload,
                )
            }
            hasUpdate -> {
                AppButton(
                    text = "更新到 v${plugin.version}",
                    onClick = onInstall,
                )
                AppButton(
                    text = "卸载",
                    onClick = onUninstall,
                    variant = AppButtonVariant.Ghost,
                    icon = Icons.Filled.Delete,
                )
            }
            else -> {
                AppButton(
                    text = "卸载",
                    onClick = onUninstall,
                    variant = AppButtonVariant.Secondary,
                    icon = Icons.Filled.Delete,
                )
            }
        }
    }
}
