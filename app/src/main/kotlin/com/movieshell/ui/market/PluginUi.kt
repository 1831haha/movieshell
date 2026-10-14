package com.movieshell.ui.market

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PluginMarket
import com.movieshell.data.repo.PluginMarket.MarketPlugin

/**
 * 插件市场共享 UI（v0.23.0）：安装/卸载动作单入口 + 按钮状态机，列表页与详情页共用。
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
    Row {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            return@Row
        }
        when {
            blocked -> {
                OutlinedButton(onClick = {}, enabled = false) {
                    Text("侠影暂不支持（${plugin.type}）")
                }
            }
            needApp -> {
                OutlinedButton(onClick = {}, enabled = false) {
                    Text("需侠影 ${plugin.minApp} 及以上")
                }
                if (installedVersion != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onUninstall) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("卸载")
                    }
                }
            }
            installedVersion == null -> {
                Button(onClick = onInstall) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("安装")
                }
            }
            hasUpdate -> {
                Button(onClick = onInstall) { Text("更新到 v${plugin.version}") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onUninstall) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("卸载")
                }
            }
            else -> {
                OutlinedButton(onClick = onUninstall) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("卸载")
                }
            }
        }
    }
}
