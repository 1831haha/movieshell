package com.movieshell.ui.preview

import com.movieshell.ui.theme.V5

import android.content.ClipData
import com.movieshell.ui.components.AppToast
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.StoragePaths
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import java.io.File

/** FileProvider authority：跟随 applicationId（debug/release 包名不同） */
fun fileProviderAuthority(context: Context) = "${context.packageName}.fileprovider"

/** 预览 kind → 对应 StoragePaths 目录（与 SourcesScreen 的 tab 对齐）。
 * v0.216.0（C16）：kind 收进白名单——未知 kind 返回 null，不再 fallback
 * 到 filesDir 下任意子目录（路由 fileName 可由外部构造，旧写法等于任意读）。 */
fun previewKindDir(context: Context, kind: String): File? = when (kind) {
    "js" -> StoragePaths.jsDir(context)
    "py" -> StoragePaths.pyDir(context)
    "php" -> PhpSourceManager.rulesDir(context)
    "web" -> StoragePaths.webDir(context)
    else -> null
}

/**
 * 路由入口唯一解析函数：kind 白名单 + 规范化路径 containment 校验。
 * fileName 含 ../、绝对路径或符号链接跳出 kind 目录时返回 null，
 * 调用方按“文件不存在”处理，不读、不分享、不外发编辑。
 */
fun resolvePreviewFile(context: Context, kind: String, fileName: String): File? {
    val dir = previewKindDir(context, kind) ?: return null
    return runCatching {
        val base = dir.canonicalFile
        val target = File(dir, fileName).canonicalFile
        if (target == base) return null
        if (!target.path.startsWith(base.path + File.separator)) return null
        target
    }.getOrNull()
}

/** 用外部编辑器打开文件（ACTION_EDIT + FileProvider uri）；失败返回 false，由调用方 Toast */
fun openFileWithEditor(context: Context, file: File): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        val edit = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(edit, "用以下应用编辑"))
        true
    } catch (e: Exception) {
        false
    }
}

/** 分享文件（ACTION_SEND + FileProvider uri）；失败返回 false，由调用方 Toast */
fun shareFile(context: Context, file: File): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享文件"))
        true
    } catch (e: Exception) {
        false
    }
}

// ---------- 轻量关键词高亮（手写关键词表，不引入高亮库） ----------

private val JS_KEYWORDS = setOf(
    "function", "return", "var", "let", "const", "if", "else", "for", "while",
    "new", "this", "true", "false", "null", "undefined",
)
private val PY_KEYWORDS = setOf(
    "def", "return", "import", "from", "class", "if", "elif", "else", "for",
    "while", "None", "True", "False", "lambda", "with", "as",
)
private val PHP_KEYWORDS = setOf(
    "function", "return", "echo", "if", "else", "foreach", "as", "class",
    "public", "private", "true", "false", "null",
)

private fun keywordsFor(kind: String): Set<String> = when (kind) {
    "js" -> JS_KEYWORDS
    "py" -> PY_KEYWORDS
    "php" -> PHP_KEYWORDS
    else -> emptySet()
}

/** 行注释标记（简单处理）：js/php 用 //，py 用 # */
private fun commentMarkFor(kind: String): String? = when (kind) {
    "js", "php" -> "//"
    "py" -> "#"
    else -> null
}

/** 拼 AnnotatedString：关键字→primary 色，行注释→次级色，其余默认。 */
private fun highlightCode(
    text: String,
    kind: String,
    keywordColor: Color,
    commentColor: Color,
): AnnotatedString {
    val keywords = keywordsFor(kind)
    val commentMark = commentMarkFor(kind)
    val kwRegex = if (keywords.isNotEmpty()) {
        Regex("\\b(" + keywords.joinToString("|") { Regex.escape(it) } + ")\\b")
    } else null
    return buildAnnotatedString {
        val lines = text.split('\n')
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (commentMark != null && trimmed.startsWith(commentMark)) {
                withStyle(SpanStyle(color = commentColor)) {
                    append(line)
                }
            } else if (kwRegex != null) {
                var last = 0
                for (m in kwRegex.findAll(line)) {
                    append(line.substring(last, m.range.first))
                    pushStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold))
                    append(m.value)
                    pop()
                    last = m.range.last + 1
                }
                append(line.substring(last))
            } else {
                append(line)
            }
            if (index != lines.lastIndex) append('\n')
        }
    }
}

/** v0.9.0：文件预览器。路由 preview/{kind}/{fileName}，kind∈js/py/php/web，只读。 */

/**
 * v0.246.0：UTF-8 安全截断——返回不超过 max 的最大完整字符边界。
 * 从 max 往回走：后续字节（10xxxxxx）跳过，找到起始字节后看该字符完整长度
 * 是否超出 max，超出则截到起始字节处。ASCII 直接返回 max。
 * 注意：必须放在 @Composable 注解之前，不能插在注解与 FilePreviewScreen 声明之间
 * （注解会错挂到本函数上，FilePreviewScreen 反而丢注解，编译失败）。
 */
private fun utf8SafeCut(bytes: ByteArray, max: Int): Int {
    if (bytes.size <= max) return bytes.size
    var cut = max
    while (cut > 0 && (bytes[cut - 1].toInt() and 0xC0) == 0x80) cut--
    if (cut == 0) return 0
    val lead = bytes[cut - 1].toInt() and 0xFF
    val len = when {
        lead < 0x80 -> 1
        lead < 0xE0 -> 2
        lead < 0xF0 -> 3
        else -> 4
    }
    // 起始字节位置是 cut-1，该字符占到 cut-1+len，超出 max 则截到起始处
    return if (cut - 1 + len > max) cut - 1 else max
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    kind: String,
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(kind, fileName) { resolvePreviewFile(context, kind, fileName) }
    val exists = file?.isFile == true

    // 大文件保护：超过 200KB 截断并提示
    // v0.246.0：原来 readText() 全文件读进内存后才截断，大文件照样 OOM，
    // 且 length 是字符数、maxBytes 是字节数，口径也不一致。
    // 改流式读：只读前 maxBytes 字节，不足 maxBytes 才算没截断。
    // v0.246.0 补（GPT 审）：截断边界退到完整 UTF-8 字符边界，不切出 �；
    // 截断判定以实际读到的字节数为准，不用解码后重编码的大小；
    // 复制提示按是否截断区分，不再截断了还说“已复制全文”。
    val maxBytes = 200 * 1024
    val previewData = remember(file) {
        if (!exists) Pair("", false) else runCatching {
            val bytes = ByteArray(maxBytes + 1)
            var read = 0
            file!!.inputStream().buffered().use { input ->
                while (read <= maxBytes) {
                    val n = input.read(bytes, read, maxBytes + 1 - read)
                    if (n < 0) break
                    read += n
                }
            }
            val truncated = read > maxBytes
            val cut = if (truncated) utf8SafeCut(bytes, maxBytes) else read
            Pair(String(bytes, 0, cut, Charsets.UTF_8), truncated)
        }.getOrDefault(Pair("", false))
    }
    val shownText = previewData.first
    val truncated = previewData.second

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            TabTopBar(
                title = fileName,
                onBack = onBack,
                actions = {
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("file", shownText))
                                            AppToast.show(context, if (truncated) "已复制预览内容（文件已截断）" else "已复制全文", long = false)
                                        },
                                        enabled = exists,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = "复制全文",
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val f = file
                                            if (f == null || !openFileWithEditor(context, f)) {
                                                AppToast.show(context, "没有可用的文本编辑器", long = true)
                                            }
                                        },
                                        enabled = exists,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "用外部编辑器打开",
                                        )
                                    }
            
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            if (!exists) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Error,
                        icon = Icons.Filled.ErrorOutline,
                        title = "文件不存在或已被删除",
                    )
                }
                return@Column
            }
            if (truncated) {
                AppCard(
                    modifier = Modifier.padding(
                        horizontal = V5.Gutter,
                        vertical = 8.dp,
                    ),
                ) {
                    Text(
                        text = "文件超过 200KB，仅显示前 200KB（只读预览不影响原文件）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            AppCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = V5.Gutter),
            ) {
                // v0.273.0（T30 余项）：高亮结果 remember 缓存——原来每次重组都
                // 对整段文本跑一遍正则高亮；文本已在 v0.246.0 限流到 200KB，
                // 这里只补缓存，文本/配色不变不重算（配色进 key，换主题重算）。
                val keywordColor = MaterialTheme.colorScheme.primary
                val commentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                val highlighted = remember(shownText, kind, keywordColor, commentColor) {
                    highlightCode(shownText, kind, keywordColor, commentColor)
                }
                Text(
                    text = highlighted,
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
