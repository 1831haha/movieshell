package com.movieshell.data.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.0.9：通用 LLM 客户端（多 provider，不锁死任何一家）。
 *
 * 三种接口形态：OPENAI 兼容（DeepSeek / 豆包 / 千问 / Kimi / 智谱 / ChatGPT /
 * 自定义）、ANTHROPIC 原生（Claude）、GEMINI 原生（Gemini）。chat 是一次性
 * 调用（测试连接、修源、写源用）；v1.1.3 起 chatMessages 发完整消息列表，
 * 供 AI 助手对话页多轮用。
 * 专用 client：不走 CmsApi.defaultClient（那只有重试拦截器与共享 CookieJar，
 * LLM 不需要；且 LLM 单次可等 60 秒以上，默认 client 整 call 60 秒不够）。
 */
object LlmClient {

    sealed interface ChatResult {
        data class Ok(val text: String, val ms: Long) : ChatResult
        data class Fail(val reason: String) : ChatResult
    }

    /** v1.1.3：多轮对话的一条消息，role 只用 user / assistant。v1.1.11：imagePath 是已压缩图片的绝对路径（调用方现解析，base64 只在发请求时现转，不落盘不进消息 JSON）。 */
    data class ChatMessage(val role: String, val content: String, val imagePath: String? = null)

    /**
     * v1.1.11：当前模型能不能看图——只按模型名里的视觉信号认，认不出来按不支持处理，
     * 宁可提示用户换模型也不静默丢图。覆盖现有预置模型里的视觉款：gpt-4o 系、Claude 3 起
     * 全系、Gemini 2.x flash、千问 vl 系（模型栏手填 qwen-vl-max 这类）、智谱 glm-4v。
     * 豆包填的是方舟接入点 ID（ep-…），从 ID 看不出支不支持，一律按不支持处理。
     */
    fun supportsVision(model: String): Boolean {
        val m = model.trim().lowercase()
        if (m.isEmpty()) return false
        return m.contains("gpt-4o")
            || m.startsWith("claude-")
            || m.startsWith("gemini-")
            || m.contains("vl")
            || m.contains("vision")
            || m.contains("4v")
    }

    /**
     * v1.1.12：到底让不让发图——名字启发式认出来就行；认不出来时看用户有没有开
     * “我的模型支持看图”（豆包方舟接入点 ID 就靠这个开关）。
     */
    fun canUseVision(context: Context, model: String): Boolean =
        AiRepairSettings.visionOverride(context) || supportsVision(model)

    /** v1.1.11：当前模型不支持视觉时，告诉用户换哪个已有的视觉模型（不新加服务商）。v1.1.12：顺带提一句视觉开关。 */
    fun visionHint(model: String): String =
        "当前模型 $model 不支持看图，图片没发出去。换个支持视觉的模型再发：ChatGPT 选 gpt-4o、Claude 选 claude-sonnet-4、Gemini 选 gemini-2.0-flash，或千问的模型栏填 qwen-vl-max。如果你的接入点本身就是视觉模型（比如豆包方舟上建的视觉接入点），在设置 - AI 助手里打开「我的模型支持看图」再发；开了只代表你确认它能看图，模型本身不支持时服务商还是会报错。"

    private val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 修复返回的 token 上限（见 chatOpenAi 注释）。 */
    private const val MAX_OUTPUT_TOKENS = 4096

    /** 测试连接：发一句最短的问候，验证 key、网络与模型是否通。 */
    suspend fun testConnection(context: Context): ChatResult =
        chat(context, "请只回一个字：好")

    /** v1.1.3 之前的一次性入口：包一层单条 user 消息走多轮，原有调用方一律不动。 */
    suspend fun chat(context: Context, prompt: String): ChatResult =
        chatMessages(context, listOf(ChatMessage("user", prompt)))

    /** v1.1.3：多轮对话——发完整消息列表；超 20 条只发最后 20 条，省 token。 */
    suspend fun chatMessages(context: Context, messages: List<ChatMessage>): ChatResult = withContext(Dispatchers.IO) {
        val provider = AiRepairSettings.provider(context)
        val apiKey = AiRepairSettings.apiKey(context)
        if (apiKey.isBlank()) return@withContext ChatResult.Fail("未填 API Key")
        val model = AiRepairSettings.model(context)
        if (model.isBlank()) return@withContext ChatResult.Fail("未填模型")
        // v1.1.2：豆包只认方舟接入点 ID（ep- 开头），填模型名方舟直接报 404；
        // 在这里先拦住，不发请求，省一次失败来回（测试连接、修源、写源、对话都走这里）。
        if (provider.id == "doubao" && !model.startsWith("ep-")) {
            return@withContext ChatResult.Fail("豆包模型要填方舟接入点 ID（ep- 开头），填模型名会报 404；去方舟控制台在线推理里建接入点，复制 ep-… 填进来")
        }
        val baseUrl = AiRepairSettings.baseUrl(context).trimEnd('/')
        if (baseUrl.isBlank()) return@withContext ChatResult.Fail("未填请求地址")
        if (messages.isEmpty()) return@withContext ChatResult.Fail("没有要发的内容")
        val tail = messages.takeLast(20)
        // v1.1.12：图片取最近 20 条里最新的一张，不只认最后一条——换模型后发文字
        // 追问也带上（只带最新的一张，不往上翻旧图）。图挂在哪条消息上不重要，
        // 发请求时统一拼在最后一条消息的内容里。
        // 不支持视觉时直接给提示、不发请求；图片文件找不到了也明说，不静默丢图。
        val imagePath = tail.asReversed().firstOrNull { !it.imagePath.isNullOrBlank() }?.imagePath
        val imageBase64: String? = if (imagePath == null) {
            null
        } else if (!canUseVision(context, model)) {
            return@withContext ChatResult.Fail(visionHint(model))
        } else {
            val bytes = runCatching { java.io.File(imagePath).readBytes() }.getOrNull()
            // ByteArray 没有 isNullOrEmpty()（stdlib 只给 Array/Collection/Map/CharSequence），
            // v1.1.11 首轮云构建 #576 同款报错修法。
            if (bytes == null || bytes.isEmpty()) {
                return@withContext ChatResult.Fail("图片文件找不到了，请重新选图再发")
            }
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
        val start = System.currentTimeMillis()
        try {
            val text = when (provider.style) {
                AiRepairSettings.ApiStyle.OPENAI -> chatOpenAi(baseUrl, apiKey, model, tail, imageBase64)
                AiRepairSettings.ApiStyle.ANTHROPIC -> chatAnthropic(baseUrl, apiKey, model, tail, imageBase64)
                AiRepairSettings.ApiStyle.GEMINI -> chatGemini(baseUrl, apiKey, model, tail, imageBase64)
            }
            ChatResult.Ok(text, System.currentTimeMillis() - start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatResult.Fail(e.message?.take(80) ?: "调用失败")
        }
    }

    /**
     * v1.1.11：带图的那条消息拼多模态内容，没图的仍按原来字符串 content 发，
     * 纯文字流程的请求体一行不动。v1.1.12：图可能来自前面某条消息，统一拼在
     * 最后一条消息上（imageBase64 只挂最后一条）。
     */
    private fun openAiContent(msg: ChatMessage, imageBase64: String?, isLast: Boolean): Any {
        if (!isLast || imageBase64 == null) return msg.content
        return JSONArray().apply {
            if (msg.content.isNotBlank()) {
                put(JSONObject().apply { put("type", "text"); put("text", msg.content) })
            }
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$imageBase64") })
            })
        }
    }

    private fun anthropicContent(msg: ChatMessage, imageBase64: String?, isLast: Boolean): Any {
        if (!isLast || imageBase64 == null) return msg.content
        return JSONArray().apply {
            if (msg.content.isNotBlank()) {
                put(JSONObject().apply { put("type", "text"); put("text", msg.content) })
            }
            put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", imageBase64)
                })
            })
        }
    }

    private fun geminiParts(msg: ChatMessage, imageBase64: String?, isLast: Boolean): JSONArray {
        return JSONArray().apply {
            if (msg.content.isNotBlank()) {
                put(JSONObject().apply { put("text", msg.content) })
            }
            if (isLast && imageBase64 != null) {
                put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", imageBase64)
                    })
                })
            }
        }
    }

    private fun chatOpenAi(baseUrl: String, apiKey: String, model: String, messages: List<ChatMessage>, imageBase64: String? = null): String {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            // v1.1.0 自查：规则原文上限 12000 字，修后全文约 3000 token；
            // 2000 会把大规则的返回截断，验证不过、白白失败，提到 4096。
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("messages", JSONArray().apply {
                messages.forEachIndexed { index, msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", openAiContent(msg, imageBase64, index == messages.lastIndex))
                    })
                }
            })
        }
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${extractError(text)}")
            val content = JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isBlank()) throw IllegalStateException("返回为空")
            content
        }
    }

    private fun chatAnthropic(baseUrl: String, apiKey: String, model: String, messages: List<ChatMessage>, imageBase64: String? = null): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("messages", JSONArray().apply {
                messages.forEachIndexed { index, msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", anthropicContent(msg, imageBase64, index == messages.lastIndex))
                    })
                }
            })
        }
        val req = Request.Builder()
            .url("$baseUrl/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${extractError(text)}")
            val content = JSONObject(text)
                .optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
            if (content.isBlank()) throw IllegalStateException("返回为空")
            content
        }
    }

    private fun chatGemini(baseUrl: String, apiKey: String, model: String, messages: List<ChatMessage>, imageBase64: String? = null): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                messages.forEachIndexed { index, msg ->
                    put(JSONObject().apply {
                        // Gemini 的 role：user 还是 user，assistant 对应 model。
                        put("role", if (msg.role == "assistant") "model" else "user")
                        put("parts", geminiParts(msg, imageBase64, index == messages.lastIndex))
                    })
                }
            })
        }
        val req = Request.Builder()
            .url("$baseUrl/models/$model:generateContent?key=$apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${extractError(text)}")
            val content = JSONObject(text)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text").orEmpty()
            if (content.isBlank()) throw IllegalStateException("返回为空")
            content
        }
    }

    /** 从错误体里抠一句可读原因（OpenAI / Anthropic / Gemini 的 error 结构各异，尽力而为）。 */
    private fun extractError(text: String): String {
        val msg = runCatching {
            val obj = JSONObject(text)
            obj.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull().orEmpty()
        return msg.ifBlank { text.take(60) }.take(60)
    }
}
