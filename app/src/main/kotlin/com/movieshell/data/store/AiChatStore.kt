package com.movieshell.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * v1.1.3：AI 助手对话的会话。
 *
 * 写入点：对话页 addUser / addAssistant / clear；
 * 读取点：对话页订阅 messages；
 * 持久化边界：filesDir/ai_chat.json（JSON 数组，IO 线程读写，失败不崩）；
 * 清空点：点“新对话”。
 * pendingSeed 与 replaceTarget 只放内存：检测页 / 写源失败直达对话时用，
 * 进对话页消费一次后清空。
 */
object AiChatStore {

    /**
     * v1.1.11：对话消息可带一张图。imagePath 只存图片文件名（filesDir/ai_chat_images 下的
     * 压缩后 JPEG），不存 base64：JSON 里只留文件名，base64 只在发请求那一刻由 LlmClient
     * 现读现转；SharedPreferences 里不放图。
     * v1.1.11：对话消息也可带一个文本文件。fileName 只存原文件名，内容落盘在
     * filesDir/ai_chat_files 下供本轮发送用，发送后即删；JSON 里只留文件名，内容不进
     * ai_chat.json、也不进 SharedPreferences；重载后只保留“发过某文件”的记录。
     */
    data class ChatMsg(val role: String, val text: String, val time: Long = System.currentTimeMillis(), val imagePath: String? = null, val fileName: String? = null)

    /** v1.1.11：待发文件（落盘在 filesDir/ai_chat_files 下，发送后即删）。 */
    data class StagedFile(
        val storedName: String,
        val displayName: String,
        val sizeBytes: Long,
        val lineCount: Int,
        val truncated: Boolean,
    )

    /** v1.1.11：选文件的结果——成功 / 不是文本文件 / 读失败。 */
    sealed interface StageFileResult {
        data class Ok(val file: StagedFile) : StageFileResult
        data object NotText : StageFileResult
        data object Failed : StageFileResult
    }

    private const val FILE_NAME = "ai_chat.json"
    private const val MAX_SEED_RULE_CHARS = 8000
    private const val MAX_SAVED = 100
    /** v1.1.11：对话图片落盘目录；发送前缩到长边 1024 内、JPEG 80，单张通常不到 200KB。 */
    private const val IMAGE_DIR = "ai_chat_images"
    private const val MAX_IMAGE_EDGE = 1024
    /** v1.1.11：待发文件落盘目录；读内容时最多读这么多字节，够截出前 8000 字。 */
    private const val FILE_DIR = "ai_chat_files"
    private const val MAX_FILE_READ_BYTES = 128 * 1024
    /** v1.1.11：认作文本文件的后缀；不在表里的按内容嗅探（见 stageFile）。 */
    private val TEXT_FILE_EXTS = setOf(
        "py", "js", "php", "json", "txt", "log", "md", "kt", "kts", "java",
        "xml", "html", "htm", "css", "yaml", "yml", "toml", "ini", "cfg",
        "conf", "sh", "csv", "ts", "tsx", "jsx", "c", "h", "cpp", "rs",
        "go", "sql", "gradle", "properties",
    )
    /** v1.1.11：常见二进制后缀（直接拒，不读内容）。 */
    private val BINARY_FILE_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "mp4", "mkv",
        "avi", "mov", "mp3", "wav", "flac", "zip", "rar", "7z", "tar",
        "gz", "apk", "dex", "so", "pdf", "doc", "docx", "xls", "xlsx",
        "exe", "bin", "db", "ttf", "otf",
    )

    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages.asStateFlow()

    var pendingSeed: String? = null
        private set
    var replaceTarget: Pair<String, String>? = null
        private set

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 进对话页时读一次落盘记录；已有消息时不覆盖（用户进页极短时间内已发消息的竞态）。 */
    suspend fun load(context: Context) {
        val parsed = withContext(Dispatchers.IO) { readFile(context) }
        if (_messages.value.isEmpty()) _messages.value = parsed
    }

    fun addUser(text: String, context: Context, imagePath: String? = null, fileName: String? = null) {
        _messages.value = _messages.value + ChatMsg("user", text, imagePath = imagePath, fileName = fileName)
        save(context)
    }

    fun addAssistant(text: String, context: Context) {
        _messages.value = _messages.value + ChatMsg("assistant", text)
        save(context)
    }

    fun clear(context: Context) {
        _messages.value = emptyList()
        pendingSeed = null
        replaceTarget = null
        save(context)
        ioScope.launch {
            File(context.filesDir, IMAGE_DIR).listFiles()?.forEach { it.delete() }
            File(context.filesDir, FILE_DIR).listFiles()?.forEach { it.delete() }
        }
    }

    /** v1.1.11：对话图片按文件名取文件；调用方只拿它显示或发请求，不直接拼路径。 */
    fun imageFile(context: Context, imageName: String): File =
        File(File(context.filesDir, IMAGE_DIR), imageName)

    /** v1.1.11：删一张已落盘的对话图片（待发图取消、或发送失败后丢弃时用）；文件不在也不报错。 */
    fun deleteImage(context: Context, imageName: String) {
        ioScope.launch { runCatching { imageFile(context, imageName).delete() } }
    }

    /**
     * v1.1.11：把系统文件选择器给的 Uri 读成对话附件。只认文本文件：后缀在
     * BINARY_FILE_EXTS 里直接拒；后缀不在 TEXT_FILE_EXTS 里时按内容嗅探，含 NUL
     * 字节的当二进制。内容过长只留前 MAX_SEED_RULE_CHARS 字，落盘到
     * filesDir/ai_chat_files 供本轮发送用；返回待发文件信息。
     * 用 GetContent 一次性授权，选完立即读，不留悬空 Uri；在 IO 线程调。
     */
    suspend fun stageFile(context: Context, uri: Uri): StageFileResult = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var rawName: String? = null
            var sizeBytes: Long = -1
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) rawName = cursor.getString(nameIdx)
                    if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
            val displayName = rawName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "文件"
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext in BINARY_FILE_EXTS) return@runCatching StageFileResult.NotText
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < MAX_FILE_READ_BYTES) {
                    val n = input.read(buf, 0, minOf(buf.size, MAX_FILE_READ_BYTES - total))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            } ?: return@runCatching StageFileResult.Failed
            val text = String(bytes, Charsets.UTF_8)
            if (ext !in TEXT_FILE_EXTS && (bytes.any { it == 0.toByte() } || text.contains('\u0000'))) {
                return@runCatching StageFileResult.NotText
            }
            val truncated = text.length > MAX_SEED_RULE_CHARS
            val content = text.take(MAX_SEED_RULE_CHARS)
            val dir = File(context.filesDir, FILE_DIR).apply { mkdirs() }
            val storedName = "file_${UUID.randomUUID()}.txt"
            File(dir, storedName).writeText(content)
            val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
            StageFileResult.Ok(
                StagedFile(
                    storedName = storedName,
                    displayName = displayName,
                    sizeBytes = if (sizeBytes >= 0) sizeBytes else bytes.size.toLong(),
                    lineCount = lines,
                    truncated = truncated,
                )
            )
        }.getOrElse { StageFileResult.Failed }
    }

    /** v1.1.11：读回本轮待发的文件内容（发送时拼进消息文本用）；读不到返回 null。 */
    suspend fun readStagedFile(context: Context, storedName: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            File(File(context.filesDir, FILE_DIR), storedName).takeIf { it.isFile }?.readText()
        }.getOrNull()
    }

    /** v1.1.11：删一份已落盘的待发文件（待发文件取消、或发送后丢弃时用）；文件不在也不报错。 */
    fun deleteStagedFile(context: Context, storedName: String) {
        ioScope.launch { runCatching { File(File(context.filesDir, FILE_DIR), storedName).delete() } }
    }

    /**
     * v1.1.11：把系统选图器给的 Uri 压成对话用图：先按 2 的幂次采样防大图整张解码
     * OOM，再精确缩到长边 1024 内、JPEG 80 落盘；返回文件名，失败返回 null。
     * 在 IO 线程调（解码 + 写盘都是阻塞操作）。
     */
    suspend fun stageImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return@runCatching null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / sample > MAX_IMAGE_EDGE * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return@runCatching null
            val scaled = if (maxOf(decoded.width, decoded.height) > MAX_IMAGE_EDGE) {
                val ratio = MAX_IMAGE_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
                val out = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
                if (out !== decoded) decoded.recycle()
                out
            } else {
                decoded
            }
            val dir = File(context.filesDir, IMAGE_DIR).apply { mkdirs() }
            val name = "img_${UUID.randomUUID()}.jpg"
            try {
                FileOutputStream(File(dir, name)).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                name
            } finally {
                scaled.recycle()
            }
        }.getOrNull()
    }

    /** 取出直达对话的首条消息，取完即清空，只发一次。 */
    fun takeSeed(): String? {
        val seed = pendingSeed
        pendingSeed = null
        return seed
    }

    /** 检测页“去对话里改”：失败原因 + 规则原文拼成首条用户消息，同时记下替换目标。 */
    fun seedFromRepair(kind: String, name: String, ruleText: String, reason: String) {
        val kindName = when (kind) {
            "py" -> "Python"
            "php" -> "PHP"
            else -> "drpy JS"
        }
        val rule = ruleText.take(MAX_SEED_RULE_CHARS)
        val note = if (ruleText.length > MAX_SEED_RULE_CHARS) "\n（规则原文过长，只贴了前 $MAX_SEED_RULE_CHARS 字。）" else ""
        pendingSeed = buildString {
            append("帮我修这条 $kindName 规则（$name），它在 App 里检测失败了。\n")
            append("失败原因：$reason\n")
            append("当前规则：\n```\n$rule\n```$note\n")
            append("只返回修好的完整规则，不要解释；我会在对话里点探活验证，通过再替换。")
        }
        replaceTarget = kind to name
    }

    /** 写源弹窗“去对话里接着改”：网址 + 失败原因拼成首条用户消息。 */
    fun seedFromWrite(url: String, reason: String) {
        pendingSeed = buildString {
            append("帮我给这个站写一条新源：$url\n")
            append("刚才在设置里写源没写成：$reason\n")
            append("你先按网址结构写，验证不过我会把日志贴给你接着改；只返回完整规则，不要解释。")
        }
        replaceTarget = null
    }

    private fun save(context: Context) {
        val snapshot = _messages.value.takeLast(MAX_SAVED)
        ioScope.launch { writeFile(context, snapshot) }
    }

    private fun readFile(context: Context): List<ChatMsg> {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.isFile) return emptyList()
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val role = obj.optString("role")
                    val text = obj.optString("text")
                    // v1.1.11：图片文件名也读回来；只发图没写字（text 为空）的消息也保留，别当成空消息丢掉。
                    val imagePath = obj.optString("image").takeIf { it.isNotBlank() }
                    // v1.1.11：发过的文件只留文件名；只发文件没写字的消息也保留。
                    val fileName = obj.optString("file").takeIf { it.isNotBlank() }
                    if (role != "user" && role != "assistant") continue
                    if (text.isBlank() && imagePath == null && fileName == null) continue
                    add(ChatMsg(role, text, obj.optLong("time", System.currentTimeMillis()), imagePath, fileName))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeFile(context: Context, snapshot: List<ChatMsg>) {
        runCatching {
            val arr = JSONArray()
            snapshot.forEach { msg ->
                arr.put(
                    JSONObject().apply {
                        put("role", msg.role)
                        put("text", msg.text)
                        put("time", msg.time)
                        // v1.1.11：只存图片文件名，不存 base64。
                        msg.imagePath?.let { put("image", it) }
                        // v1.1.11：只存原文件名，不存文件内容。
                        msg.fileName?.let { put("file", it) }
                    }
                )
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        }
    }
}
