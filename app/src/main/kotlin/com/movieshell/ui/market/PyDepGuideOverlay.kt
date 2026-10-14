package com.movieshell.ui.market

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.movieshell.data.repo.PluginMarket
import com.movieshell.data.repo.PluginStore
import com.movieshell.data.source.PyDepGuide
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import kotlinx.coroutines.launch

/**
 * v0.27.2：PY 缺库一键安装引导（MainActivity 全局覆盖层）。
 *
 * PyApi 全链路失败出口检测到 `No module named 'xxx'` 时挂起，
 * 这里弹一次对话框：一键安装「PY 引擎依赖包」。已安装/已忽略不再打扰。
 */
@Composable
fun PyDepGuideOverlay() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val missing by PyDepGuide.missingModule.collectAsState()
    var installProgress by remember { mutableStateOf<InstallProgressUi?>(null) }
    var busy by remember { mutableStateOf(false) }

    val module = missing
    if (module == null) {
        InstallProgressDialog(installProgress)
        return
    }
    // 已安装依赖包或用户点过"下次再说" → 静默清掉，不打扰
    if (!PyDepGuide.shouldBother(context, module)) {
        LaunchedEffect(module) { PyDepGuide.clear() }
        InstallProgressDialog(installProgress)
        return
    }

    AlertDialog(
        onDismissRequest = { PyDepGuide.markDismissed(context, module) },
        title = { Text("缺 PY 依赖包") },
        text = { Text("规则需要 Python 模块「$module」，安装「PY 引擎依赖包」即可（约 0.4MB，一次安装长期有效）。") },
        confirmButton = {
            AppButton(
                text = if (busy) "安装中…" else "一键安装",
                enabled = !busy,
                onClick = {
                    if (busy) return@AppButton
                    busy = true
                    scope.launch {
                        try {
                            val info = PluginMarket.fetch(PluginStore.DEFAULT_MARKET_URL)
                            val dep = info.plugins.firstOrNull { it.id == PyDepGuide.DEP_PLUGIN_ID }
                            if (dep == null) {
                                toast(context, "市场里没找到 PY 引擎依赖包")
                            } else {
                                val (ok, msg) = PluginUi.installWithProgress(context, dep) {
                                    installProgress = it
                                }
                                toast(context, msg)
                                if (ok) PyDepGuide.clear()
                            }
                        } catch (e: Exception) {
                            toast(context, "安装失败：${e.message}")
                        } finally {
                            installProgress = null
                            busy = false
                        }
                    }
                },
            )
        },
        dismissButton = {
            AppButton(
                text = "下次再说",
                variant = AppButtonVariant.Ghost,
                onClick = { PyDepGuide.markDismissed(context, module) },
            )
        },
    )
    InstallProgressDialog(installProgress)
}

private fun toast(context: Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
