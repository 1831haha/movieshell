package com.movieshell.data.repo

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
 * 自定义）、ANTHROPIC 原生（Claude）、GEMINI 原生（Gemini）。本版只供设置页
 * “测试连接”调用（发一句最短问候，验证 key、网络与模型是否通）；自动修复
 * 触发、规则校验与替换放下一版。
 * 专用 client：不走 CmsApi.defaultClient（那只有重试拦截器与共享 CookieJar，
 * LLM 不需要；且 LLM 单次可等 60 秒以上，默认 client 整 call 60 秒不够）。
 */
object LlmClient {

    sealed interface ChatResult {
        data class Ok(val text: String, val ms: Long) : ChatResult
        data class Fail(val reason: String) : ChatResult
    }

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

    suspend fun chat(context: Context, prompt: String): ChatResult = withContext(Dispatchers.IO) {
        val provider = AiRepairSettings.provider(context)
        val apiKey = AiRepairSettings.apiKey(context)
        if (apiKey.isBlank()) return@withContext ChatResult.Fail("未填 API Key")
        val model = AiRepairSettings.model(context)
        if (model.isBlank()) return@withContext ChatResult.Fail("未填模型")
        // v1.1.2：豆包只认方舟接入点 ID（ep- 开头），填模型名方舟直接报 404；
        // 在这里先拦住，不发请求，省一次失败来回（测试连接、修源、写源都走这里）。
        if (provider.id == "doubao" && !model.startsWith("ep-")) {
            return@withContext ChatResult.Fail("豆包模型要填方舟接入点 ID（ep- 开头），填模型名会报 404；去方舟控制台在线推理里建接入点，复制 ep-… 填进来")
        }
        val baseUrl = AiRepairSettings.baseUrl(context).trimEnd('/')
        if (baseUrl.isBlank()) return@withContext ChatResult.Fail("未填请求地址")
        val start = System.currentTimeMillis()
        try {
            val text = when (provider.style) {
                AiRepairSettings.ApiStyle.OPENAI -> chatOpenAi(baseUrl, apiKey, model, prompt)
                AiRepairSettings.ApiStyle.ANTHROPIC -> chatAnthropic(baseUrl, apiKey, model, prompt)
                AiRepairSettings.ApiStyle.GEMINI -> chatGemini(baseUrl, apiKey, model, prompt)
            }
            ChatResult.Ok(text, System.currentTimeMillis() - start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatResult.Fail(e.message?.take(80) ?: "调用失败")
        }
    }

    private fun chatOpenAi(baseUrl: String, apiKey: String, model: String, prompt: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            // v1.1.0 自查：规则原文上限 12000 字，修后全文约 3000 token；
            // 2000 会把大规则的返回截断，验证不过、白白失败，提到 4096。
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
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

    private fun chatAnthropic(baseUrl: String, apiKey: String, model: String, prompt: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
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

    private fun chatGemini(baseUrl: String, apiKey: String, model: String, prompt: String): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
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
