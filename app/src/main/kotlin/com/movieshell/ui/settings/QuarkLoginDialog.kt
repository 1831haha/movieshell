package com.movieshell.ui.settings

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movieshell.data.source_mgr.QuarkAuthStore
import com.movieshell.ui.components.AppToast

/**
 * v1.1.23：夸克网盘登录（挂在设置页“网盘”组里）。
 *
 * 两条路，二选一：
 * 1. 网页登录：在页里登夸克账号，再点“保存登录态”，读 WebView 的
 *    Cookie 存进 App 私有目录；
 * 2. 手填 Cookie：从浏览器开发者工具拷整串 Cookie 粘贴保存。
 * 只存 Cookie，不存账号密码；Cookie 值不进日志。
 * 游客能播约 50MB 以内的小文件，电影正片（几个 GB）必须登录，
 * 否则点播会提示先登录。
 */
@Composable
fun QuarkLoginDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        var webView by remember { mutableStateOf<WebView?>(null) }
        var loaded by remember { mutableStateOf(false) }
        var loggedIn by remember { mutableStateOf(QuarkAuthStore.hasCookie()) }
        var showManual by remember { mutableStateOf(false) }
        var manualCookie by remember { mutableStateOf("") }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("夸克网盘登录", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (loggedIn) "已登录 · 夸克分享可播" else "未登录 · 大文件播不了，先登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                AndroidView(
                    modifier = Modifier.weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    loaded = true
                                }
                            }
                            loadUrl("https://pan.quark.cn")
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() },
                )
                if (showManual) {
                    OutlinedTextField(
                        value = manualCookie,
                        onValueChange = { manualCookie = it },
                        label = { Text("粘贴整串 Cookie") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        maxLines = 3,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showManual = !showManual }) {
                        Text(if (showManual) "收起手填" else "手动填 Cookie")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (loggedIn) {
                        TextButton(onClick = {
                            QuarkAuthStore.clear()
                            loggedIn = false
                            AppToast.show(context, "已退出夸克登录")
                        }) { Text("退出登录") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (showManual) {
                        Button(onClick = {
                            if (QuarkAuthStore.saveCookie(manualCookie)) {
                                loggedIn = true
                                manualCookie = ""
                                showManual = false
                                AppToast.show(context, "已保存，请回播放页重试")
                            } else {
                                AppToast.show(context, "没粘到 Cookie，先粘贴再保存", long = true)
                            }
                        }) { Text("保存手填") }
                    } else {
                        Button(
                            enabled = loaded,
                            onClick = {
                                val cookie = CookieManager.getInstance()
                                    .getCookie("https://pan.quark.cn").orEmpty()
                                if (cookie.isBlank()) {
                                    AppToast.show(context, "还没登录，先在上方页面登夸克账号", long = true)
                                } else if (QuarkAuthStore.saveCookie(cookie)) {
                                    loggedIn = true
                                    AppToast.show(context, "已保存，请回播放页重试")
                                } else {
                                    AppToast.show(context, "保存失败，请重试", long = true)
                                }
                            },
                        ) { Text("保存登录态") }
                    }
                }
            }
        }
    }
}
