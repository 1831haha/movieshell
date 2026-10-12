package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.source.DrpyEngine
import java.io.File

/**
 * PHP 规则管理器（单例，UI 组按此契约调用）。
 *
 * 诚实标注：手机端没有 PHP 解释器，这是硬约束。
 * 生态里的 PHP 规则分两类，都跑不起来：
 * - DZ 风格 `class Spider extends BaseSpider`：需要 PHP CLI + spider.php 框架 + curl/mbstring 等扩展；
 * - T4 风格独立 PHP Web 应用：需要一台 PHP Web 服务器（如 http://127.0.0.1:9980/xxx.php），
 *   TVBox 侧只是订阅它的 HTTP 接口。
 *
 * 因此本管理器只做文件管理（列表/选中标记），所有执行函数返回空，
 * 不虚报支持。UI 导入时的 Toast 已提示"执行引擎开发中"，保持一致口径。
 */
object PhpSourceManager {

    data class RuleInfo(val name: String, val title: String, val sizeKb: Long)

    /** PHP 规则在手机端不可执行：硬约束，UI 层据此做诚实提示。 */
    const val EXECUTION_SUPPORTED = false

    const val UNSUPPORTED_REASON = "PHP 规则需要 PHP 解释器，手机端暂不支持执行"

    private const val TAG = "PhpSourceManager"
    private const val RULES_DIR = "php"

    /** 合成站源的类型哨兵：避免被 SiteRepository 的 isCms()/isPy() 误路由。 */
    private const val PHP_SITE_TYPE = -2

    private var appContext: Context? = null
    private var initialized = false

    private var activeName: String? = null

    /** init：主线程，幂等；仅创建 filesDir/php/ 目录。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        try {
            rulesDir().mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "init 建目录失败", e)
        }
    }

    /** 扫描 filesDir/php/ 下的 php 文件。 */
    fun listRules(): List<RuleInfo> {
        val dir = rulesDirOrNull() ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".php", ignoreCase = true) }
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

    /** 是否有选中的规则（注意：选中 ≠ 可执行，PHP 在手机端不可执行）。 */
    fun hasActiveRule(): Boolean = activeName != null

    /**
     * 选中规则。主线程调用；name=null 时清空。
     * 只是做选中标记，不建执行器（无执行器可用）。
     */
    fun setActiveRule(name: String?) {
        if (name == activeName) return
        activeName = null
        if (name == null) return
        val file = File(rulesDirOrNull() ?: return, name)
        if (!file.isFile) {
            Log.w(TAG, "规则文件不存在: $name")
            return
        }
        activeName = name
        Log.i(TAG, "选中 PHP 规则（仅标记，不可执行）: $name")
    }

    /**
     * 当前选中规则的合成站源（给 SiteRepository 路由用）。
     * key 带 "php:" 前缀；type 用哨兵 -2，SiteRepository 按 key 前缀路由到空实现。
     */
    fun activeSite(): Site? {
        val name = activeName ?: return null
        return Site(
            key = "php:$name",
            name = listRules().firstOrNull { it.name == name }?.title ?: name,
            type = PHP_SITE_TYPE,
            api = name,
            searchable = false
        )
    }

    // ---- 以下执行函数全部返回空：PHP 在手机端无解释器，不可执行 ----

    suspend fun home(): List<Vod> = emptyList()

    suspend fun categories(): List<CategoryType> = emptyList()

    suspend fun category(tid: String, pg: Int): List<Vod> = emptyList()

    suspend fun detail(vodId: String): VodDetail = VodDetail(vodId = vodId)

    suspend fun search(wd: String): List<Vod> = emptyList()

    suspend fun playUrl(flag: String, id: String): String = ""

    /**
     * 给 SiteRepository 用的 DrpyEngine 适配器：全部返回空结果，
     * 保持与"不支持执行"的诚实口径一致。
     */
    val engineAdapter: DrpyEngine = object : DrpyEngine {
        override fun loadRule(js: String) {
            Log.w(TAG, "PHP 规则不支持执行：$UNSUPPORTED_REASON")
        }

        override suspend fun home(): List<Vod> = emptyList()
        override suspend fun categories(): List<CategoryType> = emptyList()
        override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> = emptyList()
        override suspend fun detail(id: String): VodDetail = VodDetail(vodId = id)
        override suspend fun search(wd: String): List<Vod> = emptyList()
        override suspend fun play(flag: String, id: String): String = ""
        override fun release() {}
    }

    // ---------------- 内部 ----------------

    private fun rulesDir(): File = File(appContext!!.filesDir, RULES_DIR)

    private fun rulesDirOrNull(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, RULES_DIR).also { it.mkdirs() }
    }

    /** 从 PHP 源码头抠类名/注释名，抠不到返回 null。 */
    private fun extractTitle(file: File): String? {
        return try {
            val head = file.inputStream().use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
            Regex("""class\s+(\w+Spider)""").find(head)?.groupValues?.get(1)
                ?: Regex("""\*\s*([^\r\n*]{2,40})""").find(head)?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {
            null
        }
    }
}
