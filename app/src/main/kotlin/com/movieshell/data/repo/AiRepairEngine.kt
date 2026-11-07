package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * v1.0.9：一键 AI 修源。
 *
 * 逻辑刻意简单：只修“结构变了”的本地规则文件（js / py / php）。站挂了、超时、
 * 被拦了、本地问题的 AI 修不了，不进这个流程，检测页也不给修按钮。
 * 流程：读规则原文 → 取站点首页 HTML（取不到也不拦，照样修）→ 把“规则 +
 * 失败原因 + 首页 HTML”发给用户在设置里选好的 AI → AI 返回修复后的完整规则 →
 * 先写临时文件跑一次探活验证 → 验证通过才替换原文件（原文件先备份成
 * .bak.时间戳），验证不通过不碰原文件。
 */
object AiRepairEngine {

    sealed interface RepairResult {
        data class Ok(val ms: Long) : RepairResult
        data class Fail(val reason: String) : RepairResult
    }

    /** 规则原文与首页 HTML 的截断上限（省 token；超了在提示词里明说已截断）。 */
    private const val MAX_RULE_CHARS = 12000
    private const val MAX_HTML_CHARS = 8000

    private val URL_PATTERN = Regex("https?://[^\\s\"'<>]+")

    /** 只有这三类本地规则文件能一键修（订阅展开的 CMS 站、网页源没有本地规则文件）。 */
    fun canRepair(kind: String): Boolean = kind == "js" || kind == "py" || kind == "php"

    suspend fun repair(context: Context, kind: String, name: String, reason: String): RepairResult =
        withContext(Dispatchers.IO) {
            val file = ruleFile(context, kind, name)
                ?: return@withContext RepairResult.Fail("不支持的类型")
            if (!file.isFile) return@withContext RepairResult.Fail("规则文件丢失")
            val original = runCatching { file.readText() }.getOrNull()
                ?: return@withContext RepairResult.Fail("规则文件读不出来")
            if (original.isBlank()) return@withContext RepairResult.Fail("规则文件是空的")
            if (AiRepairSettings.apiKey(context).isBlank()) {
                return@withContext RepairResult.Fail("先去设置 - AI 修复里填 API Key")
            }
            val start = System.currentTimeMillis()
            val siteUrl = extractSiteUrl(kind, original)
            val html = siteUrl?.let { fetchHomeHtml(it) }.orEmpty()
            val prompt = buildPrompt(kind, original, reason, siteUrl, html)
            val chat = LlmClient.chat(context, prompt)
            val fixed = when (chat) {
                is LlmClient.ChatResult.Fail -> return@withContext RepairResult.Fail("AI 调用失败：${chat.reason}")
                is LlmClient.ChatResult.Ok -> extractRule(chat.text)
            }
            if (fixed.isBlank()) return@withContext RepairResult.Fail("AI 没返回可用规则")
            val probe = validateFixed(context, kind, fixed)
            when (probe) {
                is SourceProbe.Result.Ok, is SourceProbe.Result.Slow -> {
                    backup(file)
                    runCatching { file.writeText(fixed) }.onFailure {
                        return@withContext RepairResult.Fail("写回规则文件失败")
                    }
                    RepairResult.Ok(System.currentTimeMillis() - start)
                }
                is SourceProbe.Result.Fail -> RepairResult.Fail("验证没过：${probe.reason}")
                is SourceProbe.Result.Unsupported -> RepairResult.Fail("验证没过：不支持")
            }
        }

    private fun ruleFile(context: Context, kind: String, name: String): File? = when (kind) {
        "js" -> File(StoragePaths.jsDir(context), name)
        "py" -> File(StoragePaths.pyDir(context), name)
        "php" -> File(PhpSourceManager.rulesDir(context), name)
        else -> null
    }

    /** 站点地址：js 规则读 JSON 的 host；py / php 取文件里出现的第一个 http(s) 地址。 */
    private fun extractSiteUrl(kind: String, ruleText: String): String? {
        if (kind == "js") {
            val host = runCatching {
                JSONObject(ruleText).optString("host").takeIf { it.isNotBlank() }
            }.getOrNull()
            if (host != null) return host.trimEnd('/')
        }
        return URL_PATTERN.find(ruleText)?.value?.trimEnd('/')
    }

    /** 取站点首页 HTML（只读正文，去掉脚本/样式/注释并压掉多余空白；取不到返回空串，不拦修复）。 */
    private fun fetchHomeHtml(url: String): String {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_UA)
                .build()
            CmsApi.defaultClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val raw = resp.body?.string().orEmpty()
                var t = raw
                t = t.replace(Regex("(?is)<script.*?</script>"), "")
                t = t.replace(Regex("(?is)<style.*?</style>"), "")
                t = t.replace(Regex("(?is)<!--.*?-->"), "")
                t = t.replace(Regex("\\s+"), " ")
                t.trim().take(MAX_HTML_CHARS)
            }
        }.getOrNull().orEmpty()
    }

    private fun buildPrompt(kind: String, original: String, reason: String, siteUrl: String?, html: String): String {
        val typeName = when (kind) {
            "js" -> "drpy JS 规则（JSON 格式）"
            "py" -> "Python 爬虫规则（Spider 类）"
            else -> "PHP 规则"
        }
        val rule = original.take(MAX_RULE_CHARS)
        val htmlPart = if (html.isBlank()) {
            "（首页 HTML 没取到，只按规则本身与失败原因修）"
        } else {
            "```html\n$html\n```"
        }
        return buildString {
            append("你是侠影 App 的源修复助手。下面是一个 $typeName，它在 App 里检测失败。\n")
            append("失败原因：$reason\n")
            if (siteUrl != null) append("站点地址：$siteUrl\n")
            append("当前规则文件：\n```\n$rule\n```\n")
            append("该站首页当前 HTML（节选，已去掉脚本与样式）：\n$htmlPart\n")
            append("请只返回修复后的完整规则文件内容，不要解释、不要加任何其他文字。")
            append("保持规则名与站点地址不变，只修取数相关的选择器、接口或解析逻辑；")
            append("不要改规则格式（JSON 还是 JSON，Python 还是 Python）。")
            if (original.length > MAX_RULE_CHARS) append("（规则原文过长已截断，只修截断后可见的部分。）")
        }
    }

    /** 去掉 AI 返回的 markdown 代码围栏，只留规则本身。 */
    private fun extractRule(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            val firstNl = t.indexOf('\n')
            if (firstNl >= 0) t = t.substring(firstNl + 1)
            val lastFence = t.lastIndexOf("```")
            if (lastFence >= 0) t = t.substring(0, lastFence)
        }
        return t.trim()
    }

    /**
     * 验证：把修复后的规则写临时文件跑一次探活，首页能出数才算修好。
     * js / py 的临时文件放 cacheDir；php 的探活按文件名在规则目录里找文件，
     * 只能放规则目录、用完即删。
     */
    private fun validateFixed(context: Context, kind: String, fixed: String): SourceProbe.Result {
        return when (kind) {
            "js", "py" -> {
                val ext = if (kind == "js") "js" else "py"
                val tmp = File(context.cacheDir, "ai_repair_${System.currentTimeMillis()}.$ext")
                try {
                    tmp.writeText(fixed)
                    if (kind == "js") SourceProbe.probeJs(context, tmp) else SourceProbe.probePy(context, tmp)
                } finally {
                    runCatching { tmp.delete() }
                }
            }
            "php" -> {
                val tmpName = "ai_repair_tmp_${System.currentTimeMillis()}.php"
                val tmp = File(PhpSourceManager.rulesDir(context), tmpName)
                try {
                    tmp.writeText(fixed)
                    SourceProbe.probePhp(context, tmpName)
                } finally {
                    runCatching { tmp.delete() }
                }
            }
            else -> SourceProbe.Result.Fail("不支持的类型")
        }
    }

    /** 替换前备份原文件（同目录、时间戳后缀，不覆盖之前的备份）。 */
    private fun backup(file: File) {
        runCatching {
            file.copyTo(File(file.parentFile, "${file.name}.bak.${System.currentTimeMillis()}"))
        }
    }
}
