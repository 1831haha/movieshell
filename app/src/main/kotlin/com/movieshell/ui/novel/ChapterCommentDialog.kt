package com.movieshell.ui.novel

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * v1.1.3：段评最小版——章节评论入口（只看）。
 * 顶栏段评图标点开后半屏 WebView 加载站方评论页：不解析、不存、不写评论。
 * WebView 写法复用 VerifyGateDialog 的模式（JS 开、domStorage、Cookie 接受）。
 */
@Composable
fun ChapterCommentDialog(
    chapterTitle: String,
    commentUrl: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var pageUrl by remember(commentUrl) { mutableStateOf(commentUrl) }
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.62f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("段评", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            chapterTitle.ifBlank {
                                runCatching { java.net.URL(pageUrl).host }.getOrDefault(pageUrl)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "关闭")
                    }
                }
                key(commentUrl) {
                    AndroidView(
                        modifier = Modifier.weight(1f),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String) {
                                        pageUrl = url
                                    }
                                }
                                loadUrl(commentUrl)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
    }
}
