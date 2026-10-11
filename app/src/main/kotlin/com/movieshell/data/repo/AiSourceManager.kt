package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.model.toSite
import com.movieshell.data.source.ChaquopyEngine
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DrpyEngine
import com.movieshell.data.source.PyApi
import com.movieshell.data.source.TvBoxConfigParser
import com.movieshell.data.source.WebViewDrpyEngine
import java.io.File

/**
 * AI 规则管理器（单例，UI 组按此契约调用）。
 *
 * 背景：站源页「AI文件夹」是一个混合形态桶（可导入 .js / .json / .py），
 * 并没有独立的 "AI 规则规范"。本管理器按后缀分发执行：
 * - .js   -> 复用 drpy 的 WebViewDrpyEngine（独立实例，不抢 DrpySourceManager 的激活规则）
 * - .py   -> 复用 ChaquopyEngine + PyApi（按文件路径调用，不抢 PySourceManager 的激活规则）
 * - .json -> 若是 TVBox 配置形态（含有效 CMS type=0/1 站点）则按配置走 CmsApi；
 *            否则标记为 UNSUPPORTED，不硬上
 *
 * - init(context)：主线程，幂等；创建 filesDir/ai/ 目录并初始化 Chaquopy。
 * - listRules()：扫描 filesDir/ai/ 下的 js/json/py 文件。
 * - setActiveRule(name)：主线程；null=清空；切换时按后缀建执行器。
 * - 无 active rule 或形态不支持时所有 suspend 函数返回空（不抛异常）。
 * - engine：给 SiteRepository 用的 DrpyEngine 适配器，委托给当前 active 规则。
 */
object AiSourceManager {

    data class RuleInfo(val name: String, val title: String, val sizeKb: Long)

    /** AI 文件按后缀分发的执行形态。 */
    enum class AiForm {
        /** .js：drpy 规则，走 WebView 真执行 */
        JS,

        /** .py：hipy 规则，走 Chaquopy 真执行 */
        PY,

        /** .json：TVBox 配置且含可用 CMS 站点，走 CmsApi */
        JSON_CMS,

        /** 其他 / 解析不出可用站点：不支持执行 */
        UNSUPPORTED,
    }

    private const val TAG = "AiSourceManager"
    private const val RULES_DIR = "ai"

    /** 合成站源的类型哨兵：避免被 SiteRepository 的 isCms()/isPy() 误路由。 */
    private const val AI_SITE_TYPE = -1

    private var appContext: Context? = null
    private var initialized = false

    private var jsEngine: WebViewDrpyEngine? = null
    private var jsonSite: Site? = null
    private var activeName: String? = null
    private var currentForm: AiForm = AiForm.UNSUPPORTED

    private val pyApi: PyApi by lazy { PyApi() }
    private val cmsApi: CmsApi by lazy { CmsApi() }

    /** init：主线程，幂等；建目录 + 初始化 Chaquopy（供 .py 用）。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        ChaquopyEngine.init(context)
        try {
            rulesDir().mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "init 建目录失败", e)
        }
    }

    /** 扫描 filesDir/ai/ 下的 js/json/py 文件。 */
    fun listRules(): List<RuleInfo> {
        val dir = rulesDirOrNull() ?: return emptyList()
        return dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".js", ignoreCase = true) ||
                f.name.endsWith(".json", ignoreCase = true) ||
                f.name.endsWith(".py", ignoreCase = true))
        }
            .orEmpty()
            .sortedBy { it.name }
            .map { f ->
                RuleInfo(
                    name = f.name,
                    title = extractTitle(f) ?: f.nameWithoutExtension,
                    sizeKb = f.length() / 1024
                )
            }
    }

    fun activeRuleName(): String? = activeName

    fun hasActiveRule(): Boolean = activeName != null

    /** 当前激活规则的执行形态；无激活规则时返回 UNSUPPORTED。 */
    fun activeForm(): AiForm = if (activeName == null) AiForm.UNSUPPORTED else currentForm

    /** 当前激活规则是否真的能跑（给 UI 做诚实提示用）。 */
    fun isExecutable(): Boolean =
        currentForm == AiForm.JS || currentForm == AiForm.PY || currentForm == AiForm.JSON_CMS

    /**
     * 切换 active 规则。主线程调用；name=null 时清空。
     * 同名重复调用直接复用；切换时释放旧 JS 引擎，按新文件后缀建执行器。
     */
    fun setActiveRule(name: String?) {
        if (name == activeName) return
        releaseJsEngine()
        jsonSite = null
        activeName = null
        currentForm = AiForm.UNSUPPORTED
        if (name == null) return
        val file = File(rulesDirOrNull() ?: return, name)
        if (!file.isFile) {
            Log.w(TAG, "规则文件不存在: $name")
            return
        }
        val form = formOf(name)
        currentForm = when (form) {
            AiForm.JS -> {
                val ctx = appContext
                if (ctx == null) {
                    Log.w(TAG, "init 尚未调用，无法建 JS 引擎")
                    AiForm.UNSUPPORTED
                } else {
                    try {
                        val js = file.readText()
                        jsEngine = WebViewDrpyEngine(ctx).also { it.loadRule(js) }
                        AiForm.JS
                    } catch (e: Exception) {
                        Log.w(TAG, "载入 AI JS 规则失败: $name", e)
                        AiForm.UNSUPPORTED
                    }
                }
            }
            AiForm.PY -> AiForm.PY
            AiForm.JSON_CMS -> {
                val site = parseJsonSite(file)
                if (site != null) {
                    jsonSite = site
                    AiForm.JSON_CMS
                } else {
                    Log.w(TAG, "AI JSON 解析不出可用 CMS 站点: $name")
                    AiForm.UNSUPPORTED
                }
            }
            AiForm.UNSUPPORTED -> AiForm.UNSUPPORTED
        }
        activeName = name
        Log.i(TAG, "active AI 规则: $name (形态=$currentForm)")
    }

    /**
     * 当前激活规则的合成站源（给 SiteRepository 路由用）。
     * key 带 "ai:" 前缀；type 用哨兵 -1，SiteRepository 按 key 前缀优先路由。
     */
    fun activeSite(): Site? {
        val name = activeName ?: return null
        return Site(
            key = "ai:$name",
            name = listRules().firstOrNull { it.name == name }?.title ?: name,
            type = AI_SITE_TYPE,
            api = name,
            searchable = true
        )
    }

    suspend fun home(): List<Vod> = when (currentForm) {
        AiForm.JS -> runCatching { jsEngine?.home() }.getOrNull().orEmpty()
        AiForm.PY -> activeRulePath()?.let { runCatching { pyApi.home(it) }.getOrNull() }.orEmpty()
        AiForm.JSON_CMS -> jsonSite?.let { runCatching { cmsApi.home(it) }.getOrNull() }.orEmpty()
        AiForm.UNSUPPORTED -> emptyList()
    }

    suspend fun categories(): List<CategoryType> = when (currentForm) {
        AiForm.JS -> runCatching { jsEngine?.categories() }.getOrNull().orEmpty()
        AiForm.PY -> activeRulePath()?.let { runCatching { pyApi.categories(it) }.getOrNull() }.orEmpty()
        AiForm.JSON_CMS -> jsonSite?.let { runCatching { cmsApi.category(it, "", 1).first }.getOrNull() }.orEmpty()
        AiForm.UNSUPPORTED -> emptyList()
    }

    suspend fun category(tid: String, pg: Int): List<Vod> = when (currentForm) {
        AiForm.JS -> runCatching { jsEngine?.category(tid, pg) }.getOrNull().orEmpty()
        AiForm.PY -> activeRulePath()?.let { runCatching { pyApi.category(it, tid, pg) }.getOrNull() }.orEmpty()
        AiForm.JSON_CMS -> jsonSite?.let { runCatching { cmsApi.category(it, tid, pg).second }.getOrNull() }.orEmpty()
        AiForm.UNSUPPORTED -> emptyList()
    }

    suspend fun detail(vodId: String): VodDetail = when (currentForm) {
        AiForm.JS -> runCatching { jsEngine?.detail(vodId) }.getOrNull() ?: VodDetail(vodId = vodId)
        AiForm.PY -> activeRulePath()?.let { runCatching { pyApi.detail(it, vodId) }.getOrNull() }
            ?: VodDetail(vodId = vodId)
        AiForm.JSON_CMS -> jsonSite?.let { runCatching { cmsApi.detail(it, vodId) }.getOrNull() }
            ?: VodDetail(vodId = vodId)
        AiForm.UNSUPPORTED -> VodDetail(vodId = vodId)
    }

    suspend fun search(wd: String): List<Vod> = when (currentForm) {
        AiForm.JS -> runCatching { jsEngine?.search(wd) }.getOrNull().orEmpty()
        AiForm.PY -> activeRulePath()?.let { runCatching { pyApi.search(it, wd) }.getOrNull() }.orEmpty()
        AiForm.JSON_CMS -> jsonSite?.let { runCatching { cmsApi.search(it, wd) }.getOrNull() }.orEmpty()
        AiForm.UNSUPPORTED -> emptyList()
    }

    /**
     * 取播放地址。
     * - JS/PY：走各自引擎的 play（规则内部已做嗅探/解析）；
     * - JSON_CMS：CMS 的播放地址直接在详情 vod_play_url 里，无需引擎 play，返回空串，
     *   调用方用 SiteRepository.parseEpisodes() 对齐分集。
     */
    suspend fun playUrl(flag: String, id: String): String = when (currentForm) {
        AiForm.JS -> runCatching { jsEngine?.play(flag, id) }.getOrNull().orEmpty()
        AiForm.PY -> activeRulePath()?.let { runCatching { pyApi.play(it, flag, id).first }.getOrNull() }.orEmpty()
        AiForm.JSON_CMS, AiForm.UNSUPPORTED -> ""
    }

    /**
     * 给 SiteRepository 用的 DrpyEngine 适配器：
     * 委托给当前 active 的 AI 规则；无 active 规则或形态不支持时返回空结果。
     */
    val engineAdapter: DrpyEngine = object : DrpyEngine {
        override fun loadRule(js: String) {
            Log.w(TAG, "适配器不支持直接 loadRule，请走 setActiveRule(name)")
        }

        override suspend fun home(): List<Vod> = this@AiSourceManager.home()
        override suspend fun categories(): List<CategoryType> =
            this@AiSourceManager.categories()

        override suspend fun category(tid: String, pg: Int): List<Vod> =
            this@AiSourceManager.category(tid, pg)

        override suspend fun detail(id: String): VodDetail = this@AiSourceManager.detail(id)
        override suspend fun search(wd: String): List<Vod> = this@AiSourceManager.search(wd)
        override suspend fun play(flag: String, id: String): String =
            this@AiSourceManager.playUrl(flag, id)

        override fun release() {
            // 生命周期归 setActiveRule 管，适配器不销毁
        }
    }

    // ---------------- 内部 ----------------

    private fun rulesDir(): File = File(appContext!!.filesDir, RULES_DIR)

    private fun rulesDirOrNull(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, RULES_DIR).also { it.mkdirs() }
    }

    private fun activeRulePath(): String? {
        val dir = rulesDirOrNull() ?: return null
        val name = activeName ?: return null
        val f = File(dir, name)
        return if (f.isFile) f.absolutePath else null
    }

    private fun releaseJsEngine() {
        try {
            jsEngine?.release()
        } catch (e: Exception) {
            Log.w(TAG, "旧 JS 引擎 release 异常", e)
        }
        jsEngine = null
    }

    private fun formOf(name: String): AiForm = when {
        name.endsWith(".js", ignoreCase = true) -> AiForm.JS
        name.endsWith(".py", ignoreCase = true) -> AiForm.PY
        name.endsWith(".json", ignoreCase = true) -> AiForm.JSON_CMS
        else -> AiForm.UNSUPPORTED
    }

    /**
     * 解析 AI JSON 文件：先按 TVBox 订阅 JSON 解析；没有 sites 时尝试把
     * 顶层对象本身当作单条站点声明（包一层 {"sites":[...]} 复用容忍式解析）。
     * 只接受 CMS 形态（type=0/1）且 api/key 有效的站点，取第一条。
     */
    private fun parseJsonSite(file: File): Site? {
        return try {
            val raw = file.readText().trim()
            if (raw.isEmpty()) return null
            val config = TvBoxConfigParser.parseConfig(raw)
            val sites = if (config.sites.isNotEmpty()) config.sites
            else TvBoxConfigParser.parseConfig("{\"sites\":[$raw]}").sites
            sites
                .filter { it.api.isNotBlank() && it.key.isNotBlank() }
                .map { it.toSite() }
                .firstOrNull { it.type == 0 || it.type == 1 }
        } catch (e: Exception) {
            Log.w(TAG, "AI JSON 解析失败: ${file.name}", e)
            null
        }
    }

    /** 从文件头抠标题：js 找 title / py 找 Spider 类名 / json 找 name 字段。 */
    private fun extractTitle(file: File): String? {
        return try {
            val head = file.inputStream().use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
            when {
                file.name.endsWith(".js", ignoreCase = true) ->
                    Regex("""title\s*:\s*['"]([^'"]{1,40})['"]""").find(head)?.groupValues?.get(1)
                file.name.endsWith(".py", ignoreCase = true) ->
                    Regex("""class\s+(\w+Spider)""").find(head)?.groupValues?.get(1)
                file.name.endsWith(".json", ignoreCase = true) ->
                    Regex(""""name"\s*:\s*"([^"]{1,40})"""").find(head)?.groupValues?.get(1)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
