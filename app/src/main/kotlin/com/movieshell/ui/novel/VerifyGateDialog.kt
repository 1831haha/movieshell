package com.movieshell.ui.novel

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * v1.2.23：改小窗（94% 宽 × 78% 高、16dp 圆角居中，不再全屏）+「验证完成」
 * 按钮随时可点——原先按钮要等 onPageFinished 才启用，站方页面一直转圈加载时
 * 按钮永远是灰的，用户明明过了验证也点不了（2026-11-17 用户报障实证）。
 * 用户本人判断验证做完了，点按钮即取当前页 HTML + Cookie；页还没加载完时取到的
 * 是当前内容，引擎拿不到页会按失败处理，不比原来更糟。
 * v1.2.24：再收小、做精致——88% 宽 × 60% 高、20dp 大圆角、标题行换 X 关闭、
 * 分隔线、底部只剩「取消 / 验证完成」两个按钮，提示文案去掉（标题+域名已够说明）。
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
        Surface(
            Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.60f),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cur.title, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            runCatching { java.net.URL(pageUrl).host }.getOrDefault(pageUrl),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { VerifyGate.complete(null) }) {
                        Icon(Icons.Filled.Close, contentDescription = "取消")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
                                    }
                                }
                                loadUrl(cur.url)
                                webView = this
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { VerifyGate.complete(null) }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
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
