package com.movieshell.ui.settings

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.movieshell.data.source_mgr.AliyunAuthStore
import com.movieshell.data.source_mgr.BaiduAuthStore
import com.movieshell.data.source_mgr.GuangyaAuthStore
import com.movieshell.data.source_mgr.PanProvider
import com.movieshell.data.source_mgr.PanQrSession
import com.movieshell.data.source_mgr.QrState
import com.movieshell.data.source_mgr.QuarkAuthStore
import com.movieshell.data.source_mgr.UcAuthStore
import com.movieshell.data.source_mgr.XunleiAuthStore
import com.movieshell.ui.components.AppToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * v1.1.25：网盘扫码登录通用弹窗（六家共用，按批准的效果图：居中小卡片，
 * 标题＋二维码＋“请使用 XX APP 扫码登录”＋状态行＋刷新二维码按钮＋
 * 手动输入入口）。不再嵌 WebView 登录页。
 *
 * 二维码本地画（ZXing；夸克 180x180 画布口径，这里画 480px 供高清显示），
 * 百度直接用 getqrcode 返回的 imgurl。过期必须能一键刷新。
 * 手动输入入口保留兜底：夸克/UC/百度粘贴整串 Cookie，阿里/光鸭/迅雷
 * 粘贴 token；Cookie/token 值不进日志。
 */
@Composable
fun PanQrLoginDialog(provider: PanProvider, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(provider) { PanQrSession(provider, scope) }
    val state = session.state.collectAsState().value
    var showManual by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val qrContent = (state as? QrState.Ready)?.qrContent
    val qrImageUrl = (state as? QrState.Ready)?.qrImageUrl
    // 迅雷/光鸭扫码未接通、阿里用户定先不做扫码：弹窗直接展开手动输入，不让用户干等。
    val manualFirst = provider == PanProvider.GUANGYA || provider == PanProvider.XUNLEI ||
        provider == PanProvider.ALIYUN

    LaunchedEffect(session) { session.start() }
    DisposableEffect(session) { onDispose { session.cancel() } }
    LaunchedEffect(qrContent) {
        bitmap = if (qrContent != null) withContext(Dispatchers.Default) { qrBitmap(qrContent, 480) }
        else null
    }
    LaunchedEffect(manualFirst) { if (manualFirst) showManual = true }
    LaunchedEffect(state) {
        if (state is QrState.Success) {
            AppToast.show(context, "已登录")
            delay(700)
            onDismiss()
        }
    }

    fun saveManual(): Boolean {
        val text = manualText.trim()
        if (text.isBlank()) return false
        return when (provider) {
            PanProvider.QUARK -> QuarkAuthStore.saveCookie(text)
            PanProvider.UC -> UcAuthStore.saveCookie(text)
            PanProvider.BAIDU -> BaiduAuthStore.saveCookie(text)
            PanProvider.ALIYUN -> AliyunAuthStore.saveTokenJson(text)
            PanProvider.GUANGYA -> GuangyaAuthStore.saveTokenJson(text)
            PanProvider.XUNLEI -> XunleiAuthStore.saveTokenJson(text)
        }
    }

    val manualLabel = when (provider) {
        PanProvider.QUARK, PanProvider.UC, PanProvider.BAIDU -> "粘贴整串 Cookie"
        PanProvider.ALIYUN -> "粘贴 access_token（或完整 token JSON）"
        PanProvider.GUANGYA, PanProvider.XUNLEI -> "粘贴登录 token"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        text = "${provider.displayName}扫码登录",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("关闭") }
                }
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.size(200.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            state is QrState.Loading -> CircularProgressIndicator()
                            state is QrState.Expired -> Text(
                                text = "二维码已过期\n请点刷新重取",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Black,
                            )
                            state is QrState.Error -> Text(
                                text = (state as QrState.Error).message,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Black,
                                modifier = Modifier.padding(12.dp),
                            )
                            bitmap != null -> Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = "扫码登录二维码",
                                modifier = Modifier.size(184.dp),
                            )
                            qrImageUrl != null -> AsyncImage(
                                model = qrImageUrl,
                                contentDescription = "扫码登录二维码",
                                modifier = Modifier.size(184.dp),
                            )
                            else -> CircularProgressIndicator()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "请使用 ${provider.appName} APP 扫码登录",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state is QrState.Ready || state is QrState.Loading || state is QrState.Exchanging) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = when (state) {
                            QrState.Loading -> "正在取二维码…"
                            is QrState.Ready -> "等待扫码…"
                            QrState.Scanned -> "已扫码，请在手机上确认"
                            QrState.Exchanging -> "扫码成功，正在换取登录态…"
                            QrState.Success -> "登录成功"
                            QrState.Expired -> "二维码已过期，请刷新重取"
                            is QrState.Error -> (state as QrState.Error).message
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state is QrState.Error) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { session.refresh() },
                    enabled = state !is QrState.Loading && state !is QrState.Exchanging,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("刷新二维码") }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showManual = !showManual }) {
                    Text(
                        text = if (showManual) "收起手动输入"
                        else if (provider == PanProvider.QUARK || provider == PanProvider.UC || provider == PanProvider.BAIDU) "手动输入 Cookie"
                        else "手动输入 Token",
                        textDecoration = TextDecoration.Underline,
                    )
                }
                if (showManual) {
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        label = { Text(manualLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (saveManual()) {
                                AppToast.show(context, "已保存")
                                onDismiss()
                            } else {
                                AppToast.show(context, "没粘到内容，先粘贴再保存", long = true)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存") }
                }
            }
        }
    }
}

/** 二维码本地画：ZXing QRCodeWriter → 黑白 Bitmap；失败返回 null。 */
private fun qrBitmap(content: String, sizePx: Int): Bitmap? = runCatching {
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (bits.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    bmp
}.getOrNull()
