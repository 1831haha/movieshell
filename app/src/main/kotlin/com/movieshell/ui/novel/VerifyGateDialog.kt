package com.movieshell.ui.novel

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movieshell.data.novel.VerifyGate
import org.json.JSONObject

/**
 * v0.201.0：验证通道的界面端（挂在 MainActivity 根 Box 里，全局悬浮）。
 * 有请求时弹全屏 WebView：用户在页里手动过验证（滑验证码/点确认），再点
 * 「验证完成」——取整页 HTML + WebView Cookie 回填给引擎；取消/返回则回填 null。
 * 不自动判定验证成功（Cloudflare 形态多变，误判比让用户点一下更糟）。
 */
@Composable
fun VerifyGateDialog() {
    val req by VerifyGate.request.collectAsState()
    val cur = req ?: return
    Dialog(
        onDismissRequest = { VerifyGate.complete(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        var webView by remember(cur.url) { mutableStateOf<WebView?>(null) }
        var pageUrl by remember(cur.url) { mutableStateOf(cur.url) }
        var loaded by remember(cur.url) { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cur.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            runCatching { java.net.URL(pageUrl).host }.getOrDefault(pageUrl),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { VerifyGate.complete(null) }) { Text("取消") }
                }
                key(cur.url) {
                    AndroidView(
                        modifier = Modifier.weight(1f),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                cur.ua?.let { settings.userAgentString = it }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String) {
                                        pageUrl = url
                                        loaded = true
                                    }
                                }
                                loadUrl(cur.url)
                                webView = this
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "在上方页面完成验证，再点验证完成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = loaded,
                        onClick = {
                            val view = webView ?: run {
                                VerifyGate.complete(null)
                                return@Button
                            }
                            view.evaluateJavascript(
                                "(function(){return document.documentElement.outerHTML})()",
                            ) { raw ->
                                val html = decodeJsResult(raw)
                                // 原地址与落地地址的 Cookie 都带上（验证可能跨域），按名去重、落地地址优先
                                val cookies = listOf(cur.url, pageUrl)
                                    .flatMap { u ->
                                        CookieManager.getInstance().getCookie(u).orEmpty().split(";")
                                    }
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .distinct()
                                    .joinToString("; ")
                                VerifyGate.complete(VerifyGate.Result(html, pageUrl, cookies))
                            }
                        },
                    ) { Text("验证完成") }
                }
            }
        }
    }
}

/** evaluateJavascript 回调是 JSON 字符串字面量，包一层 {"v":...} 再取即得原文（嗅探屏同款）。 */
private fun decodeJsResult(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrDefault("")
}
