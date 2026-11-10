package com.movieshell.data.ai

import android.content.Context
import com.movieshell.core.util.StoragePaths
import com.movieshell.data.source.CmsApi
import com.movieshell.core.net.DEFAULT_UA
import com.movieshell.data.source_mgr.PhpSourceManager
import com.movieshell.data.source_mgr.SourceProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URL

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

    /** 从一段文本里认规则类型：能抠出规则才非空，供对话页决定给不给“探活验证”按钮。 */
    fun detectKind(ruleText: String): String? = when {
        ruleText.contains("var rule") -> "js"
        ruleText.contains("class Spider") -> "py"
        ruleText.contains("BaseSpider") -> "php"
        else -> null
    }

    /** 检测页“去对话里改”用：读一条本地规则的原文，读不到返回 null。 */
    fun readRuleText(context: Context, kind: String, name: String): String? {
        // v1.1.21：无授权时只读内置规则原文。
        if (!StoragePaths.hasAccess(context) && !StoragePaths.isBuiltinRule(name)) return null
        val file = ruleFile(context, kind, name) ?: return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 对话页“探活验证”用：只探活不保存，复用 validateFixed。 */
    suspend fun probeRule(context: Context, kind: String, ruleText: String): SourceProbe.Result =
        withContext(Dispatchers.IO) { validateFixed(context, kind, ruleText) }

    sealed interface ApplyResult {
        data class Ok(val fileName: String, val ms: Long) : ApplyResult
        data class Fail(val reason: String) : ApplyResult
    }

    /**
     * v1.1.3：对话里改好的规则落盘——先探活，不过不保存；
     * 通过后 replaceFile 非空则备份后写回原文件，为空则按类型写新文件（同名自动加 -2、-3）。
     */
    suspend fun applyRuleFromChat(
        context: Context,
        kind: String,
        nameHint: String,
        ruleText: String,
        replaceFile: File?,
    ): ApplyResult = withContext(Dispatchers.IO) {
        if (!canRepair(kind)) return@withContext ApplyResult.Fail("不支持的类型")
        if (ruleText.isBlank()) return@withContext ApplyResult.Fail("规则是空的")
        // v1.1.21：新写一条规则是写用户文件，先授权；写回已显示的规则
        // （无授权时只剩内置规则）不拦，落点仍在私有回退目录。
        if (replaceFile == null && !StoragePaths.hasAccess(context)) {
            return@withContext ApplyResult.Fail("需要先授予存储权限，才能保存新规则")
        }
        val start = System.currentTimeMillis()
        when (val probe = validateFixed(context, kind, ruleText)) {
            is SourceProbe.Result.Ok, is SourceProbe.Result.Slow -> {
                if (replaceFile != null) {
                    backup(replaceFile)
                    runCatching { replaceFile.writeText(ruleText) }.onFailure {
                        return@withContext ApplyResult.Fail("写回规则文件失败")
                    }
                    ApplyResult.Ok(replaceFile.name, System.currentTimeMillis() - start)
                } else {
                    val fileName = uniqueFileName(context, nameHint, ruleText, "", kind)
                    val target = File(targetDir(context, kind), fileName)
                    runCatching { target.writeText(ruleText) }.onFailure {
                        return@withContext ApplyResult.Fail("保存规则文件失败")
                    }
                    ApplyResult.Ok(fileName, System.currentTimeMillis() - start)
                }
            }
            is SourceProbe.Result.Fail -> ApplyResult.Fail("验证没过：${probe.reason}")
            is SourceProbe.Result.Unsupported -> ApplyResult.Fail("验证没过：不支持")
        }
    }

    suspend fun repair(context: Context, kind: String, name: String, reason: String): RepairResult =
        withContext(Dispatchers.IO) {
            // v1.1.21：无授权时只修内置规则。
            if (!StoragePaths.hasAccess(context) && !StoragePaths.isBuiltinRule(name)) {
                return@withContext RepairResult.Fail("需要先授予存储权限，才能修复本地规则")
            }
            val file = ruleFile(context, kind, name)
                ?: return@withContext RepairResult.Fail("不支持的类型")
            if (!file.isFile) return@withContext RepairResult.Fail("规则文件丢失")
            val original = runCatching { file.readText() }.getOrNull()
                ?: return@withContext RepairResult.Fail("规则文件读不出来")
            if (original.isBlank()) return@withContext RepairResult.Fail("规则文件是空的")
            if (AiRepairSettings.apiKey(context).isBlank()) {
                return@withContext RepairResult.Fail("先去设置 - AI 助手里填 API Key")
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

    sealed interface WriteResult {
        data class Ok(val fileName: String, val ms: Long, val kind: String = "js") : WriteResult
        data class Fail(val reason: String) : WriteResult
    }

    /**
     * v1.1.1：AI 写新源（JS / PY / PHP 三选，保存位置由类型定）。
     * v1.1.2：kind = "auto" 时先让 AI 按首页结构定类型（默认 drpy JS），再按定下的
     * 类型走原流程；定类型失败就默认 drpy JS。流程：网址（没写 http 开头自动补
     * https）→ 取站点首页 HTML（取不到也继续，只告诉 AI 没取到）→ AI 按首页结构
     * 写完整规则 → 先写临时文件跑一次探活验证 → 首页能出数才保存，验证不过不
     * 保存、不留半成品。保存位置：js → xiaying/js/，py → xiaying/py/，
     * php → xiaying/php/t4_demo/。源名：用户填了就用用户填的，没填就从 AI 返回
     * 的 title 里取，再不行用域名；同名文件已存在时自动加 -2、-3，不覆盖已有源。
     */
    suspend fun writeSource(context: Context, rawUrl: String, nameHint: String, kind: String = "js"): WriteResult =
        withContext(Dispatchers.IO) {
            // v1.1.21：写新源是写用户文件，先授权再跑，避免 AI 白跑一轮才发现存不下。
            if (!StoragePaths.hasAccess(context)) {
                return@withContext WriteResult.Fail("需要先授予存储权限，再用 AI 写源")
            }
            val url = normalizeUrl(rawUrl)
                ?: return@withContext WriteResult.Fail("网址不对，请填完整网址，如 https://www.xxx.com")
            if (AiRepairSettings.apiKey(context).isBlank()) {
                return@withContext WriteResult.Fail("先去设置 - AI 助手里填 API Key")
            }
            val start = System.currentTimeMillis()
            val html = fetchHomeHtml(url)
            val realKind = if (kind == "auto") classifyKind(context, url, html) else kind
            if (!canRepair(realKind)) return@withContext WriteResult.Fail("不支持的类型")
            val prompt = buildWritePrompt(url, html, realKind)
            val chat = LlmClient.chat(context, prompt)
            val ruleText = when (chat) {
                is LlmClient.ChatResult.Fail -> return@withContext WriteResult.Fail("AI 调用失败：${chat.reason}")
                is LlmClient.ChatResult.Ok -> extractRule(chat.text)
            }
            if (ruleText.isBlank()) return@withContext WriteResult.Fail("AI 没返回可用规则")
            val markerOk = when (realKind) {
                "js" -> ruleText.contains("var rule")
                "py" -> ruleText.contains("class Spider")
                else -> ruleText.contains("BaseSpider")
            }
            if (!markerOk) {
                return@withContext WriteResult.Fail("AI 返回的不是 $realKind 规则，没保存")
            }
            val probe = validateFixed(context, realKind, ruleText)
            when (probe) {
                is SourceProbe.Result.Ok, is SourceProbe.Result.Slow -> {
                    val fileName = uniqueFileName(context, nameHint, ruleText, url, realKind)
                    val target = File(targetDir(context, realKind), fileName)
                    runCatching { target.writeText(ruleText) }.onFailure {
                        return@withContext WriteResult.Fail("保存规则文件失败")
                    }
                    WriteResult.Ok(fileName, System.currentTimeMillis() - start, realKind)
                }
                is SourceProbe.Result.Fail -> WriteResult.Fail("验证没过：${probe.reason}，没保存")
                is SourceProbe.Result.Unsupported -> WriteResult.Fail("验证没过：不支持，没保存")
            }
        }

    /**
     * v1.1.2：写源“自动选”——只看首页结构定类型。绝大多数站默认 drpy JS；
     * 只有站明显需要登录会话、复杂交互、drpy 写不出来时选 py；只有站是 PHP
     * 规则站时选 php。AI 没给准话或调用失败都默认 js，不拦写源。
     */
    private suspend fun classifyKind(context: Context, url: String, html: String): String {
        val prompt = buildString {
            append("只看下面这个视频站的首页结构，判断写它的规则最适合用哪种类型，只回 js、py、php 三个词中的一个，不要解释、不要加任何其他文字。\n")
            append("站点地址：$url\n")
            append("默认选 js；只有站明显需要登录会话、复杂交互、drpy JS 写不出来时选 py；只有站是 PHP 规则站时选 php。\n")
            if (html.isBlank()) append("（首页 HTML 没取到，只按网址判断。）\n")
            else append("首页 HTML（节选）：\n```html\n$html\n```\n")
        }
        return when (val chat = LlmClient.chat(context, prompt)) {
            is LlmClient.ChatResult.Fail -> "js"
            is LlmClient.ChatResult.Ok -> {
                val t = chat.text.lowercase()
                when {
                    t.contains("php") -> "php"
                    t.contains("py") -> "py"
                    else -> "js"
                }
            }
        }
    }

    private fun targetDir(context: Context, kind: String): File = when (kind) {
        "py" -> StoragePaths.pyDir(context)
        "php" -> PhpSourceManager.rulesDir(context)
        else -> StoragePaths.jsDir(context)
    }

    private fun normalizeUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isBlank()) return null
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return runCatching {
            val u = URL(withScheme)
            if (u.host.isNullOrBlank()) null else withScheme.trimEnd('/')
        }.getOrNull()
    }

    private fun buildWritePrompt(url: String, html: String, kind: String): String {
        val htmlPart = if (html.isBlank()) {
            "（首页 HTML 没取到：该站可能拦了直接请求，只按网址本身写，验证不过就直说。）"
        } else {
            "```html\n$html\n```"
        }
        val format = when (kind) {
            "py" -> "只返回完整的 Python 规则文件内容，不要解释、不要加任何其他文字。\n" +
                "格式：先写 import re、sys、json、urllib.parse 与 BeautifulSoup，再写 sys.path.append('..') 与 from base.spider import Spider，" +
                "然后 class Spider(Spider):，方法签名必须一字不差：homeContent(self, filter)、homeVideoContent(self)、" +
                "categoryContent(self, tid, pg=1, filter=None, extend=None)、detailContent(self, ids)、" +
                "searchContent(self, key, quick=False, pg=1)、playerContent(self, flag, id, vipFlags=None)。\n" +
                "homeContent 返回 {\"class\": [{\"type_id\", \"type_name\"}], \"list\": [...]}，分类用首页真实分类；" +
                "detailContent 的 ids 是列表，逐个取详情，vod_play_from 与 vod_play_url 用 $$$ 分线路、用 # 分集、用 $ 分集名和地址；" +
                "playerContent 里 id 是详情给的播放地址原值，找到 mp4 或 m3u8 直链返回 {\"parse\": 0, \"url\": 直链}，" +
                "找不到就返回 {\"parse\": 1, \"url\": 播放页地址}，绝不编造不存在的地址或接口。\n"
            "php" -> "只返回完整的 PHP 规则文件内容，不要解释、不要加任何其他文字。\n" +
                "格式：开头 require_once __DIR__ . '/spider.php';，然后 class 某站 Spider extends BaseSpider，" +
                "方法签名必须一字不差：homeContent(\$filter)、homeVideoContent()、categoryContent(\$tid, \$pg = 1, \$filter = [], \$extend = [])、" +
                "detailContent(\$ids)、searchContent(\$key, \$quick = false, \$pg = 1)、playerContent(\$flag, \$id, \$vipFlags = [])，" +
                "结尾写 \$spider = new 某站 Spider(); \$spider->run();。\n" +
                "取数用 \$this->fetch(\$url)、\$this->pdfa(\$html, 规则)、\$this->pdfh(\$html, 规则)，分类分页用 \$this->pageResult(\$list, \$pg)；" +
                "detailContent 的 \$ids 是列表，vod_play_from 与 vod_play_url 用 $$$ 分线路、用 # 分集、用 \$ 分集名和地址；" +
                "playerContent 里 \$id 是详情给的播放地址原值，直链返回 ['parse' => 0, 'url' => 直链]，" +
                "找不到直链返回 ['parse' => 1, 'url' => 播放页地址]，绝不编造不存在的地址或接口。\n"
            else -> "只返回完整的 JS 规则文件内容，不要解释、不要加任何其他文字。\n" +
                "格式必须是 var rule = { title, host, homeUrl, url, searchUrl, class_name, class_url, " +
                "推荐, 一级, 二级, 搜索, 播放 }；title 用站名，host 必须是上面给的站点地址原样，homeUrl 是 /。\n" +
                "所有章节函数都要是 async function，凡是 request、pdfh、pdfa、fetch、post 调用前面一律加 await，" +
                "取到数据后用 setResult 交出去，不要用同步写法。\n" +
                "推荐：请求 homeUrl，从首页链接里取片名、详情页地址、封面；一级：请求 this.input，按列表页结构取数；" +
                "二级：请求详情页，取片名、封面、简介与选集，vod_play_from 与 vod_play_url 用 $$$ 分线路、用 # 分集、用 $ 分集名和地址；" +
                "搜索：searchUrl 里用 ** 代关键词，按列表结构取数；播放：播放页里找到 mp4 或 m3u8 直链就返回 { url, parse: 0 }，" +
                "找不到直链就返回播放页地址 { url, parse: 1 }，绝不编造不存在的地址或接口。\n"
        }
        return buildString {
            append("你是侠影 App 的写源助手，要给一个视频站写一条全新的规则。\n")
            append("站点地址：$url\n")
            append("该站首页当前 HTML（节选，已去掉脚本与样式）：\n$htmlPart\n")
            append(format)
            append("只按首页 HTML 里真实出现的链接与结构写；看不清分类就用首页已有的分类，分类写不全也不要编。")
        }
    }

    /** 写源用的文件名：用户填的名优先，其次 AI 规则里的 title，再次域名；同名自动加 -2、-3。 */
    private fun uniqueFileName(context: Context, nameHint: String, ruleText: String, url: String, kind: String): String {
        val fromRule = Regex("title\\s*:\\s*['\"]([^'\"]+)['\"]").find(ruleText)?.groupValues?.getOrNull(1).orEmpty()
        val host = runCatching { URL(url).host.removePrefix("www.") }.getOrNull().orEmpty()
        val baseRaw = nameHint.trim().ifBlank { fromRule.trim() }.ifBlank { host.substringBefore('.') }.ifBlank { "ai-source" }
        val base = baseRaw.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5_-]"), "").take(30).ifBlank { "ai-source" }
        val dir = targetDir(context, kind)
        val ext = when (kind) {
            "py" -> "py"
            "php" -> "php"
            else -> "js"
        }
        var candidate = "$base.$ext"
        var n = 2
        while (File(dir, candidate).isFile) {
            candidate = "$base-$n.$ext"
            n++
        }
        return candidate
    }

    /** 对话页用：一条本地规则文件的位置（kind + 文件名）。 */
    fun ruleFile(context: Context, kind: String, name: String): File? = when (kind) {
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

    /**
     * 去掉 AI 返回的 markdown 代码围栏，只留规则本身。
     * v1.1.3：对话里 AI 常在规则前后加解释，改成抠第一个围栏块；以前只返回
     * 整段规则的用法不受影响（无围栏原样返回，纯规则加围栏同以前一样剥掉）。
     */
    fun extractRule(text: String): String {
        val t = text.trim()
        val firstFence = t.indexOf("```")
        if (firstFence < 0) return t
        val firstNl = t.indexOf('\n', firstFence)
        val bodyStart = if (firstNl >= 0) firstNl + 1 else firstFence + 3
        val lastFence = t.lastIndexOf("```")
        val bodyEnd = if (lastFence > bodyStart) lastFence else t.length
        return t.substring(bodyStart, bodyEnd).trim()
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
