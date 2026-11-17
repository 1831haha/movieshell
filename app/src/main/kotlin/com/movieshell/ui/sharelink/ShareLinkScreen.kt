package com.movieshell.ui.sharelink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.movieshell.data.sharelink.PlayLink
import com.movieshell.data.sharelink.QrState
import com.movieshell.data.sharelink.ShareAuth
import com.movieshell.data.sharelink.ShareFile
import com.movieshell.data.sharelink.ShareLink
import com.movieshell.data.sharelink.ShareListing
import com.movieshell.data.sharelink.SharePlatform
import com.movieshell.data.sharelink.ShareQrSession
import com.movieshell.data.sharelink.ShareResolver
import com.movieshell.data.sharelink.ShareResult
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class DirCrumb(val title: String, val dirKey: String)

/**
 * v1.3.5：分享链接播剧页——只做粘贴链接播，不做网盘中心。
 *
 * 流程：粘贴链接 → 解析列目录（文件夹可下钻）→ 点一集取直链 → 交播放器。
 * 有登录态拿全长直链；没登录时夸克/UC 大文件只给 2 分钟预览（先弹窗明说），
 * 百度没登录基本播不了（取直链要 SVIP），阿里/光鸭/迅雷游客直通。
 * 登录入口在顶栏“登录网盘”，每盘登一次、只存本机。
 * 空态/加载中/失败态都有可见反馈，不静默空白。
 */
@Composable
fun ShareLinkScreen(
    onBack: () -> Unit,
    onPlay: (url: String, title: String, headers: Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var linkText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var errorNeedLogin by remember { mutableStateOf(false) }
    var errorPlatform by remember { mutableStateOf<SharePlatform?>(null) }
    var listing by remember { mutableStateOf<ShareListing?>(null) }
    var dirStack by remember { mutableStateOf(listOf<DirCrumb>()) }
    var resolvingFid by remember { mutableStateOf<String?>(null) }
    var loginTick by remember { mutableStateOf(0) }
    var showLogin by remember { mutableStateOf(false) }
    var loginPlatform by remember { mutableStateOf<SharePlatform?>(null) }
    var showQrLogin by remember { mutableStateOf(false) }
    var showManualLogin by remember { mutableStateOf(false) }
    var pendingPreview by remember { mutableStateOf<PlayLink?>(null) }
    var pendingPreviewTitle by remember { mutableStateOf("") }

    // ---- 局部函数（先声明后调用） ----

    fun openLoginFor(platform: SharePlatform) {
        loginPlatform = platform
        showLogin = false
        showQrLogin = platform == SharePlatform.QUARK ||
            platform == SharePlatform.UC || platform == SharePlatform.BAIDU
        showManualLogin = !showQrLogin
    }

    fun parseRoot(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            error = "先粘贴一个分享链接"
            errorNeedLogin = false
            errorPlatform = null
            return
        }
        if (busy) return
        busy = true
        busyText = "正在解析分享…"
        error = ""
        errorNeedLogin = false
        errorPlatform = null
        listing = null
        dirStack = emptyList()
        scope.launch {
            when (val r = ShareResolver.listDir(trimmed)) {
                is ShareResult.Ok -> {
                    listing = r.value
                    dirStack = listOf(DirCrumb(r.value.title.ifBlank { "分享" }, ""))
                }
                is ShareResult.Err -> {
                    error = r.message
                    errorNeedLogin = r.needLogin
                    errorPlatform = ShareLink.platformOf(trimmed)
                }
            }
            busy = false
        }
    }

    fun openDir(file: ShareFile) {
        val cur = listing ?: return
        if (busy) return
        busy = true
        busyText = "正在打开文件夹…"
        error = ""
        errorNeedLogin = false
        errorPlatform = null
        scope.launch {
            when (val r = ShareResolver.listDir(cur.shareUrl, file.dirKey)) {
                is ShareResult.Ok -> {
                    listing = r.value
                    dirStack = dirStack + DirCrumb(file.name, file.dirKey)
                }
                is ShareResult.Err -> {
                    error = r.message
                    errorNeedLogin = r.needLogin
                    errorPlatform = cur.platform
                }
            }
            busy = false
        }
    }

    fun goBackDir() {
        if (dirStack.size > 1) {
            val cur = listing ?: return
            val parent = dirStack[dirStack.size - 2]
            if (busy) return
            busy = true
            busyText = "正在返回上级…"
            error = ""
            errorNeedLogin = false
            errorPlatform = null
            scope.launch {
                when (val r = ShareResolver.listDir(cur.shareUrl, parent.dirKey)) {
                    is ShareResult.Ok -> {
                        listing = r.value
                        dirStack = dirStack.dropLast(1)
                    }
                    is ShareResult.Err -> {
                        error = r.message
                        errorNeedLogin = r.needLogin
                        errorPlatform = cur.platform
                    }
                }
                busy = false
            }
        } else {
            onBack()
        }
    }

    fun playFile(file: ShareFile) {
        val cur = listing ?: return
        if (resolvingFid != null) return
        resolvingFid = file.fid
        error = ""
        errorNeedLogin = false
        errorPlatform = null
        scope.launch {
            when (val r = ShareResolver.resolve(cur, file)) {
                is ShareResult.Ok -> {
                    if (r.value.isPreview) {
                        pendingPreview = r.value
                        pendingPreviewTitle = file.name
                    } else {
                        onPlay(r.value.url, file.name, r.value.headers)
                    }
                }
                is ShareResult.Err -> {
                    error = r.message
                    errorNeedLogin = r.needLogin
                    errorPlatform = cur.platform
                }
            }
            resolvingFid = null
        }
    }

    fun pasteFromClipboard() {
        val text = clipboard.getText()?.text.orEmpty().trim()
        if (text.isBlank()) {
            AppToast.show(context, "剪贴板里没有文字，先去复制分享链接")
        } else {
            linkText = text
        }
    }

    BackHandler { goBackDir() }

    val currentListing = listing
    val recognized = remember(linkText) { ShareLink.platformOf(linkText) }

    Scaffold(
        topBar = {
            TabTopBar(
                title = "分享链接播剧",
                onBack = { goBackDir() },
                actions = {
                    TextButton(onClick = { showLogin = true }) { Text("登录网盘") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TechTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    placeholder = "粘贴分享链接，如 https://pan.quark.cn/s/…",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                AppButton(
                    text = "解析",
                    onClick = { parseRoot(linkText) },
                    enabled = !busy && linkText.isNotBlank(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (recognized != null) "认出是${recognized.displayName}分享" else "支持夸克、UC、百度、阿里、光鸭、迅雷的分享链接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { pasteFromClipboard() }) { Text("粘贴") }
            }
            Spacer(Modifier.height(4.dp))
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = busyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (error.isNotBlank()) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (errorNeedLogin && errorPlatform != null) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { openLoginFor(errorPlatform!!) }) {
                                Text("去登录${errorPlatform!!.displayName}")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (currentListing == null && !busy && error.isBlank()) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "怎么用",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "1. 把群里、网上的分享链接粘进来，点解析\n" +
                                "2. 有提取码的链接连提取码一起粘过来\n" +
                                "3. 大文件要先登录对应网盘，不然夸克/UC 只能看前 2 分钟，百度基本播不了",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (currentListing != null) {
                LoginHintRow(
                    platform = currentListing.platform,
                    loginTick = loginTick,
                    onLoginClick = { openLoginFor(currentListing.platform) },
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dirStack.lastOrNull()?.title ?: currentListing.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (dirStack.size > 1) {
                        TextButton(onClick = { goBackDir() }) { Text("上级") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (currentListing.files.isEmpty()) {
                    Text(
                        text = "这个文件夹是空的",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(currentListing.files, key = { it.fid + it.name }) { file ->
                            if (file.isDir) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { openDir(file) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { playFile(file) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val meta = listOfNotNull(
                                            ShareLink.humanSize(file.size).takeIf { it.isNotBlank() },
                                            ShareLink.humanDuration(file.durationSec).takeIf { it.isNotBlank() },
                                        ).joinToString(" · ")
                                        if (meta.isNotBlank()) {
                                            Text(
                                                text = meta,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (resolvingFid == file.fid) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 弹窗（每个调用处都有对应的 if 守卫） ----

    if (showLogin) {
        ShareLoginDialog(
            onDismiss = { showLogin = false },
            onPick = { openLoginFor(it) },
            onChanged = { loginTick++ },
        )
    }
    val qrPlatform = loginPlatform
    if (showQrLogin && qrPlatform != null) {
        ShareQrLoginDialog(
            platform = qrPlatform,
            onDismiss = { showQrLogin = false },
            onManual = { showQrLogin = false; showManualLogin = true },
            onSuccess = { loginTick++ },
        )
    }
    val manualPlatform = loginPlatform
    if (showManualLogin && manualPlatform != null) {
        ShareManualLoginDialog(
            platform = manualPlatform,
            onDismiss = { showManualLogin = false },
            onSaved = { loginTick++ },
        )
    }
    val preview = pendingPreview
    if (preview != null) {
        TechDialog(
            onDismissRequest = { pendingPreview = null },
            title = "只能看前 2 分钟",
            confirmText = "先看 2 分钟",
            onConfirm = {
                pendingPreview = null
                onPlay(preview.url, pendingPreviewTitle, preview.headers)
            },
        ) {
            Text(
                text = preview.note.ifBlank { "没登录，只能看前 2 分钟，登录后看全集" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    pendingPreview = null
                    currentListing?.let { openLoginFor(it.platform) }
                },
            ) { Text("去登录，看全集") }
        }
    }
}

/** 当前盘的登录提示行：没登录/已过期才出现，阿里/光鸭/迅雷游客直通不提示。 */
@Composable
private fun LoginHintRow(
    platform: SharePlatform,
    loginTick: Int,
    onLoginClick: () -> Unit,
) {
    if (platform != SharePlatform.QUARK && platform != SharePlatform.UC &&
        platform != SharePlatform.BAIDU
    ) {
        return
    }
    val loggedIn = remember(loginTick) { ShareAuth.hasLogin(platform) }
    val expired = remember(loginTick) { ShareAuth.isExpired(platform) }
    val text = when {
        !loggedIn && platform == SharePlatform.BAIDU -> "没登录百度：取直链要 SVIP，先登录"
        !loggedIn -> "没登录${platform.displayName}：大文件只能看前 2 分钟"
        expired -> "${platform.displayName}登录已过期，点重登"
        else -> return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (expired) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onLoginClick) { Text(if (loggedIn) "重登" else "去登录") }
    }
}

/** 登录列表弹窗：六盘一行一态，扫码盘点登录进二维码弹窗，token 盘点登录进手动输入弹窗。 */
@Composable
private fun ShareLoginDialog(
    onDismiss: () -> Unit,
    onPick: (SharePlatform) -> Unit,
    onChanged: () -> Unit,
) {
    var tick by remember { mutableStateOf(0) }
    TechDialog(
        onDismissRequest = onDismiss,
        title = "登录网盘",
        confirmText = "完成",
        onConfirm = onDismiss,
    ) {
        Text(
            text = "登录只存本机，Cookie 和 token 不会上传。登录后大文件才能看全集。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SharePlatform.values().forEach { platform ->
            val loggedIn = remember(tick) { ShareAuth.hasLogin(platform) }
            val expired = remember(tick) { ShareAuth.isExpired(platform) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = platform.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            !loggedIn -> "未登录"
                            expired -> "登录已过期"
                            else -> "已登录"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loggedIn) {
                    TextButton(
                        onClick = {
                            ShareAuth.clear(platform)
                            tick++
                            onChanged()
                        },
                    ) { Text("退出") }
                }
                AppButton(
                    text = if (loggedIn) "重登" else "登录",
                    onClick = { onPick(platform) },
                    compact = true,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "阿里、光鸭、迅雷不用登录也能播分享；登录只备用。夸克扫码换取偶尔失败，失败时点手动输入 Cookie。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 扫码登录弹窗（只给夸克/UC/百度三家，旧 PanQrLoginDialog 同款流程）。 */
@Composable
private fun ShareQrLoginDialog(
    platform: SharePlatform,
    onDismiss: () -> Unit,
    onManual: () -> Unit,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(platform) { ShareQrSession(platform, scope) }
    val state by session.state.collectAsState()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val qrContent = (state as? QrState.Ready)?.qrContent
    val qrImageUrl = (state as? QrState.Ready)?.qrImageUrl
    DisposableEffect(platform) {
        session.start()
        onDispose { session.cancel() }
    }
    LaunchedEffect(qrContent) {
        bitmap = if (qrContent != null) {
            withContext(Dispatchers.Default) { qrBitmap(qrContent, 480) }
        } else {
            null
        }
    }
    LaunchedEffect(state) {
        if (state is QrState.Success) {
            AppToast.show(context, "登录成功")
            onSuccess()
            onDismiss()
        }
    }
    TechDialog(
        onDismissRequest = onDismiss,
        title = "${platform.displayName}扫码登录",
        confirmText = "完成",
        onConfirm = onDismiss,
    ) {
        Text(
            text = "请用${platform.appName} App 扫码",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "登录二维码",
                    modifier = Modifier.size(220.dp),
                )
                qrImageUrl != null -> AsyncImage(
                    model = qrImageUrl,
                    contentDescription = "登录二维码",
                    modifier = Modifier.size(220.dp),
                )
                state is QrState.Loading -> CircularProgressIndicator()
                state is QrState.Error -> Text(
                    text = (state as QrState.Error).message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                state is QrState.Expired -> Text(
                    text = "二维码已过期，点刷新重取",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val statusText = when (state) {
            is QrState.Loading -> "正在取二维码…"
            is QrState.Ready -> "等待扫码"
            is QrState.Scanned -> "已扫码，手机上确认登录"
            is QrState.Exchanging -> "正在换取登录态…"
            is QrState.Expired -> "二维码已过期"
            is QrState.Error -> (state as QrState.Error).message
            is QrState.Success -> "登录成功"
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = "刷新二维码",
                onClick = { session.refresh() },
                enabled = state !is QrState.Loading && state !is QrState.Exchanging,
                compact = true,
            )
        }
        TextButton(onClick = onManual) { Text("手动输入 Cookie") }
    }
}

/** 手动登录弹窗：扫码盘贴 Cookie，阿里贴 access_token（或含它的 JSON），光鸭/迅雷贴 token。 */
@Composable
private fun ShareManualLoginDialog(
    platform: SharePlatform,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val isCookie = platform == SharePlatform.QUARK ||
        platform == SharePlatform.UC || platform == SharePlatform.BAIDU
    val label = when (platform) {
        SharePlatform.ALIYUN -> "粘贴 access_token，或含 access_token 的 JSON"
        else -> if (isCookie) "粘贴 Cookie（一整串）" else "粘贴 Token"
    }
    TechDialog(
        onDismissRequest = onDismiss,
        title = "手动登录 · ${platform.displayName}",
        confirmText = "保存",
        onConfirm = {
            val ok = when {
                isCookie -> ShareAuth.saveCookie(platform, text)
                platform == SharePlatform.ALIYUN -> {
                    val v = text.trim()
                    if (v.startsWith("{")) ShareAuth.saveTokenJson(platform, v)
                    else ShareAuth.saveTokenJson(
                        platform,
                        JSONObject().put("access_token", v).toString(),
                    )
                }
                else -> ShareAuth.saveTokenJson(platform, text)
            }
            if (ok) {
                AppToast.show(context, "已保存")
                onSaved()
                onDismiss()
            } else {
                AppToast.show(context, "没粘到内容，先粘贴再保存", long = true)
            }
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TechTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = label,
            singleLine = false,
            minLines = 3,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "只存本机，不会上传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 二维码内容画成 ARGB 位图（照搬旧 PanQrLoginDialog 末尾实现）。 */
private fun qrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bmp.setPixel(x, y, if (bits[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}
