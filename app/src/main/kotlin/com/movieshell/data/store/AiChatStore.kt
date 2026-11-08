package com.movieshell.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
     */
    data class ChatMsg(val role: String, val text: String, val time: Long = System.currentTimeMillis(), val imagePath: String? = null)

    private const val FILE_NAME = "ai_chat.json"
    private const val MAX_SEED_RULE_CHARS = 8000
    private const val MAX_SAVED = 100
    /** v1.1.11：对话图片落盘目录；发送前缩到长边 1024 内、JPEG 80，单张通常不到 200KB。 */
    private const val IMAGE_DIR = "ai_chat_images"
    private const val MAX_IMAGE_EDGE = 1024

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

    fun addUser(text: String, context: Context, imagePath: String? = null) {
        _messages.value = _messages.value + ChatMsg("user", text, imagePath = imagePath)
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
                    if (role != "user" && role != "assistant") continue
                    if (text.isBlank() && imagePath == null) continue
                    add(ChatMsg(role, text, obj.optLong("time", System.currentTimeMillis()), imagePath))
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
                    }
                )
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        }
    }
}
