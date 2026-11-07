package com.movieshell.data.repo

import android.content.Context

/**
 * v1.0.9：AI 助手设置（多 provider，不锁死任何一家）。
 *
 * 预置 DeepSeek / 豆包 / 千问 / Kimi / 智谱 / ChatGPT / Claude / Gemini / 自定义，
 * 用户在设置里选一家、填自己的 API Key 与模型。Key 与模型按 provider 分开存，
 * 切 provider 不丢之前填过的 key。Key 只存本机 SharedPreferences，不上传、
 * 不进备份（与 OpenList token 同口径：明文存本机）。
 * 国外三家（ChatGPT / Claude / Gemini）在国内网络下可能调不通，需 VPN；
 * 国内优先 DeepSeek 或豆包。豆包的模型填方舟接入点 ID（ep-…）。
 */
object AiRepairSettings {

    private const val PREFS = "ai_repair_prefs"
    private const val KEY_PROVIDER = "ai_provider"
    private const val KEY_BASE_URL = "ai_base_url"

    enum class ApiStyle { OPENAI, ANTHROPIC, GEMINI }

    data class AiProvider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val defaultModel: String,
        val style: ApiStyle,
        /** 国内直连通常可达；国外 provider 在国内网络下可能调不通 */
        val domestic: Boolean = true,
        /** v1.1.1：内置可选模型，点选即可，不用手打记名字；空表示没有固定模型名，须手填（如豆包的方舟接入点 ID、自定义）。 */
        val models: List<String> = listOf(defaultModel).filter { it.isNotBlank() },
    )

    val PRESETS = listOf(
        AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash", ApiStyle.OPENAI, models = listOf("deepseek-flash", "deepseek-v4-pro")),
        AiProvider("doubao", "豆包", "https://ark.cn-beijing.volces.com/api/v3", "", ApiStyle.OPENAI, models = emptyList()),
        AiProvider("qwen", "千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", ApiStyle.OPENAI, models = listOf("qwen-plus", "qwen-turbo", "qwen-max")),
        AiProvider("kimi", "Kimi", "https://api.moonshot.cn/v1", "kimi-k2", ApiStyle.OPENAI, models = listOf("kimi-k2", "moonshot-v1-8k", "moonshot-v1-32k")),
        AiProvider("glm", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4", ApiStyle.OPENAI, models = listOf("glm-4", "glm-4-plus", "glm-4-flash")),
        AiProvider("openai", "ChatGPT", "https://api.openai.com/v1", "gpt-4o-mini", ApiStyle.OPENAI, domestic = false, models = listOf("gpt-4o-mini", "gpt-4o")),
        AiProvider("claude", "Claude", "https://api.anthropic.com/v1", "claude-sonnet-4-20250514", ApiStyle.ANTHROPIC, domestic = false, models = listOf("claude-sonnet-4-20250514", "claude-opus-4-20250514")),
        AiProvider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash", ApiStyle.GEMINI, domestic = false, models = listOf("gemini-2.0-flash", "gemini-2.5-flash")),
        AiProvider("custom", "自定义", "", "", ApiStyle.OPENAI, models = emptyList()),
    )

    fun provider(context: Context): AiProvider {
        val id = prefs(context).getString(KEY_PROVIDER, "deepseek") ?: "deepseek"
        return PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()
    }

    fun setProvider(context: Context, id: String) {
        prefs(context).edit().putString(KEY_PROVIDER, id).apply()
    }

    /** API Key 按 provider 分开存，切 provider 不丢之前填过的 key。 */
    fun apiKey(context: Context): String {
        val id = provider(context).id
        return prefs(context).getString("ai_key_$id", "") ?: ""
    }

    fun setApiKey(context: Context, v: String) {
        val id = provider(context).id
        prefs(context).edit().putString("ai_key_$id", v.trim()).apply()
    }

    /** 模型按 provider 分开存；没填过就用当前 provider 的默认值（豆包默认空，须填方舟接入点 ID）。 */
    fun model(context: Context): String {
        val p = provider(context)
        val saved = prefs(context).getString("ai_model_${p.id}", "") ?: ""
        if (saved.isNotBlank()) return saved.trim()
        return p.defaultModel
    }

    fun setModel(context: Context, v: String) {
        val id = provider(context).id
        prefs(context).edit().putString("ai_model_$id", v.trim()).apply()
    }

    /** 请求地址：自定义 provider 用用户填的，否则用预置地址。 */
    fun baseUrl(context: Context): String {
        val p = provider(context)
        if (p.id == "custom") return prefs(context).getString(KEY_BASE_URL, "") ?: ""
        return p.baseUrl
    }

    fun setBaseUrl(context: Context, v: String) {
        prefs(context).edit().putString(KEY_BASE_URL, v.trim()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
