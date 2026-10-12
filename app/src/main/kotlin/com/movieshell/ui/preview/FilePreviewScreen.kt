package com.movieshell.ui.preview

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.movieshell.data.repo.StoragePaths
import java.io.File

/** FileProvider authority：与 AndroidManifest 里的 provider 声明一致 */
const val FILE_PROVIDER_AUTHORITY = "com.movieshell.debug.fileprovider"

/** 预览 kind → 对应 StoragePaths 目录（与 SourcesScreen 的 tab 对齐） */
fun previewKindDir(context: Context, kind: String): File = when (kind) {
    "js" -> StoragePaths.jsDir(context)
    "py" -> StoragePaths.pyDir(context)
    "php" -> StoragePaths.phpDir(context)
    "web" -> StoragePaths.webDir(context)
    else -> File(context.filesDir, kind)
}

/** 用外部编辑器打开文件（ACTION_EDIT + FileProvider uri）；失败返回 false，由调用方 Toast */
fun openFileWithEditor(context: Context, file: File): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
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
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
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

/** 拼 AnnotatedString：关键字→primary 色，行注释→灰色，其余默认。 */
private fun highlightCode(text: String, kind: String, keywordColor: Color): AnnotatedString {
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
                withStyle(SpanStyle(color = Color.Gray)) {
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    kind: String,
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(kind, fileName) { File(previewKindDir(context, kind), fileName) }
    val exists = file.isFile

    // 大文件保护：超过 200KB 截断并提示
    val maxBytes = 200 * 1024
    val fullText = remember(file) {
        if (exists) runCatching { file.readText() }.getOrDefault("") else ""
    }
    val truncated = fullText.length > maxBytes
    val shownText = if (truncated) fullText.substring(0, maxBytes) else fullText

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("file", fullText))
                            Toast.makeText(context, "已复制全文", Toast.LENGTH_SHORT).show()
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
                            if (!openFileWithEditor(context, file)) {
                                Toast.makeText(context, "没有可用的文本编辑器", Toast.LENGTH_LONG).show()
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
                Text(
                    text = "文件不存在或已被删除",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            if (truncated) {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Text(
                        text = "文件超过 200KB，仅显示前 200KB（只读预览不影响原文件）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            CodeText(
                text = highlightCode(shownText, kind, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 等宽只读文本 */
@Composable
private fun CodeText(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = modifier,
    )
}
