package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.source.ChaquopyEngine
import com.movieshell.data.source.DrpyEngine
import com.movieshell.data.source.PyApi
import java.io.File

/**
 * PY 规则源管理器（单例，UI 组按此契约调用）。
 *
 * - init(context)：主线程，幂等；首次把 assets/py/ 下的 .py 规则
 *   拷贝到 filesDir/py/。
 * - listRules()：扫描 filesDir/py/ 下的 py 文件。
 * - setActiveRule(name)：主线程；null=清空；切换只换文件名，
 *   Python 解释器由 ChaquopyEngine 懒启动并全局复用。
 * - 无 active rule 时所有 suspend 函数返回空（不抛异常）。
 */
object PySourceManager {

    data class RuleInfo(val name: String, val title: String, val sizeKb: Long)

    private const val TAG = "PySourceManager"
    private const val RULES_DIR = "py"

    private var appContext: Context? = null
    private var initialized = false

    private var activeName: String? = null
    private val api: PyApi by lazy { PyApi() }

    /** init：主线程，幂等；首次把 assets/py/ 下规则拷贝到 filesDir/py/。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        ChaquopyEngine.init(context)
        try {
            val dir = rulesDir()
            dir.mkdirs()
            val assets = appContext!!.assets
            (assets.list(RULES_DIR) ?: emptyArray())
                .filter { it.endsWith(".py") }
                .forEach { name ->
                    val dst = File(dir, name)
                    if (!dst.exists()) {
                        assets.open("$RULES_DIR/$name").use { input ->
                            dst.outputStream().use { input.copyTo(it) }
                        }
                        Log.i(TAG, "拷贝内置规则: $name")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "init 拷贝规则失败", e)
        }
    }

    /** 扫描 filesDir/py/ 下的 py 文件。 */
    fun listRules(): List<RuleInfo> {
        val dir = rulesDirOrNull() ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".py") }
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

    /** 切换 active 规则。主线程调用；name=null 时清空。 */
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
        Log.i(TAG, "active PY 规则: $name")
    }

    private fun activeRulePath(): String? {
        val dir = rulesDirOrNull() ?: return null
        val name = activeName ?: return null
        return File(dir, name).absolutePath
    }

    suspend fun home(): List<Vod> =
        activeRulePath()?.let { runCatching { api.home(it) }.getOrNull() }.orEmpty()

    suspend fun categories(): List<CategoryType> =
        activeRulePath()?.let { runCatching { api.categories(it) }.getOrNull() }.orEmpty()

    suspend fun category(tid: String, pg: Int, filter: Map<String, String> = emptyMap()): List<Vod> =
        activeRulePath()?.let { runCatching { api.category(it, tid, pg) }.getOrNull() }.orEmpty()

    suspend fun detail(vodId: String): VodDetail =
        activeRulePath()?.let { runCatching { api.detail(it, vodId) }.getOrNull() }
            ?: VodDetail(vodId = vodId)

    suspend fun search(wd: String): List<Vod> =
        activeRulePath()?.let { runCatching { api.search(it, wd) }.getOrNull() }.orEmpty()

    suspend fun playUrl(flag: String, id: String): String =
        activeRulePath()?.let { runCatching { api.play(it, flag, id).first }.getOrNull() }
            .orEmpty()

    /**
     * 给 SiteRepository 构造器用的 DrpyEngine 适配器：
     * 委托给当前 active 的 PY 规则；无 active 规则时返回空结果。
     */
    val engineAdapter: DrpyEngine = object : DrpyEngine {
            override fun loadRule(js: String) {
                Log.w(TAG, "适配器不支持直接 loadRule，请走 setActiveRule(name)")
            }

            override suspend fun home(): List<Vod> = this@PySourceManager.home()
            override suspend fun categories(): List<CategoryType> =
                this@PySourceManager.categories()

            override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> =
                this@PySourceManager.category(tid, pg, filter)

            override suspend fun detail(id: String): VodDetail = this@PySourceManager.detail(id)
            override suspend fun search(wd: String): List<Vod> = this@PySourceManager.search(wd)
            override suspend fun play(flag: String, id: String): String =
                this@PySourceManager.playUrl(flag, id)

            override fun release() {
                // 引擎生命周期由 ChaquopyEngine 懒启动管理，适配器不销毁
            }
        }

    // ---------------- 内部 ----------------

    private fun rulesDir(): File = File(appContext!!.filesDir, RULES_DIR)

    private fun rulesDirOrNull(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, RULES_DIR).also { it.mkdirs() }
    }

    /** 从规则源码里抠类名/中文名（class XxxSpider / # 注释），抠不到返回 null。 */
    private fun extractTitle(file: File): String? {
        return try {
            val head = file.inputStream().use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
            Regex("""class\s+(\w+Spider)""").find(head)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}
