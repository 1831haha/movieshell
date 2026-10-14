package com.movieshell.ui.market

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
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

    /** 安装/更新：返回 toast 文案。 */
    suspend fun install(context: Context, p: MarketPlugin): String = try {
        PluginMarket.install(context, p)
        "已安装 ${p.name} v${p.version}"
    } catch (e: Exception) {
        LogBus.e("PluginMarket", "安装失败：${e.message}（${p.id}）")
        "安装失败：${e.message}"
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
