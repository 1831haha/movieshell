package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.source.DrpyEngine
import com.movieshell.data.source.QuickJsDrpyEngine
import java.io.File

/**
 * drpy 规则源管理器（单例，UI 组按此契约调用）。
 *
 * - init(context)：主线程，幂等；首次把 assets/drpy/ 下的规则（除垫片）
 *   拷贝到 StoragePaths.jsDir()（/storage/emulated/0/xiaying/js/)。
 * - listRules()：扫描 StoragePaths.jsDir()（/storage/emulated/0/xiaying/js/) 下的 js 文件。
 * - setActiveRule(name)：主线程；null=清空；切换时为该规则创建/复用
 *   QuickJsDrpyEngine 并 loadRule。
 * - 无 active rule 时所有 suspend 函数返回空（不抛异常）。
 * - engine：给 SiteRepository 用的 DrpyEngine 适配器，委托给当前 active 引擎。
 */
object DrpySourceManager {

    data class RuleInfo(val name: String, val title: String, val sizeKb: Long)

    private const val TAG = "DrpySourceManager"
    private const val RULES_DIR = "drpy"
    private const val SHIM_NAME = "shim.js"

    private var appContext: Context? = null
    private var initialized = false

    private var engine: QuickJsDrpyEngine? = null
    private var activeName: String? = null

    /** init：主线程，幂等；首次把 assets/drpy/ 下规则拷贝到 StoragePaths.jsDir()（/storage/emulated/0/xiaying/js/)。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        try {
            val dir = rulesDir()
            dir.mkdirs()
            val assets = appContext!!.assets
            (assets.list(RULES_DIR) ?: emptyArray())
                .filter { it.endsWith(".js") && it != SHIM_NAME }
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

    /** 扫描 StoragePaths.jsDir()（/storage/emulated/0/xiaying/js/) 下的 js 文件。 */
    fun listRules(): List<RuleInfo> {
        val dir = rulesDirOrNull() ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".js") && f.name != SHIM_NAME }
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

    fun hasActiveRule(): Boolean = engine != null && activeName != null

    /**
     * 切换 active 规则。主线程调用；name=null 时清空。
     * 同名重复调用直接复用；切换时销毁旧引擎、为新规则建 QuickJsDrpyEngine 并 loadRule。
     */
    fun setActiveRule(name: String?) {
        if (name == activeName) return
        try {
            engine?.release()
        } catch (e: Exception) {
            Log.w(TAG, "旧引擎 release 异常", e)
        }
        engine = null
        activeName = null
        if (name == null) return
        val file = File(rulesDirOrNull() ?: return, name)
        if (!file.isFile) {
            Log.w(TAG, "规则文件不存在: $name")
            return
        }
        try {
            val js = file.readText()
            val ctx = appContext ?: return
            // v0.112.0 D2：删重复 release——旧引擎已在函数开头 try/catch 里释放并置空，
            // 此处的 engine?.release() 恒为 no-op。保留开头那一次。
            engine = QuickJsDrpyEngine(ctx).also { it.loadRule(js) }
            activeName = name
            Log.i(TAG, "active 规则: $name")
        } catch (e: Exception) {
            Log.w(TAG, "载入规则失败: $name", e)
        }
    }

    suspend fun home(): List<Vod> =
        runCatching { engine?.home() }.getOrNull().orEmpty()

    suspend fun categories(): List<CategoryType> =
        runCatching { engine?.categories() }.getOrNull().orEmpty()

    suspend fun category(tid: String, pg: Int, filter: Map<String, String> = emptyMap()): List<Vod> =
        runCatching { engine?.category(tid, pg, filter) }.getOrNull().orEmpty()

    /** 当前规则某分类的筛选定义（无筛选时空列表）。 */
    suspend fun filters(tid: String): List<FilterGroup> =
        runCatching { engine?.filters(tid) }.getOrNull().orEmpty()

    suspend fun detail(vodId: String): VodDetail =
        runCatching { engine?.detail(vodId) }.getOrNull() ?: VodDetail(vodId = vodId)

    suspend fun search(wd: String): List<Vod> =
        runCatching { engine?.search(wd) }.getOrNull().orEmpty()

    suspend fun playUrl(flag: String, id: String): String =
        runCatching { engine?.play(flag, id) }.getOrNull().orEmpty()

    /**
     * 给 SiteRepository 构造器用的 DrpyEngine 适配器：
     * 委托给当前 active 规则的引擎；无 active 规则时返回空结果。
     */
    val drpyEngineAdapter: DrpyEngine = object : DrpyEngine {
        override fun loadRule(js: String) {
            Log.w(TAG, "适配器不支持直接 loadRule，请走 setActiveRule(name)")
        }

        override suspend fun home(): List<Vod> = this@DrpySourceManager.home()
        override suspend fun categories(): List<CategoryType> = this@DrpySourceManager.categories()
        override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> =
            this@DrpySourceManager.category(tid, pg, filter)

        override suspend fun filters(tid: String): List<FilterGroup> =
            this@DrpySourceManager.filters(tid)

        override suspend fun detail(id: String): VodDetail = this@DrpySourceManager.detail(id)
        override suspend fun search(wd: String): List<Vod> = this@DrpySourceManager.search(wd)
        override suspend fun play(flag: String, id: String): String =
            this@DrpySourceManager.playUrl(flag, id)

        override fun release() {
            // 生命周期归 setActiveRule 管，适配器不销毁
        }
    }

    // ---------------- 内部 ----------------

    private fun rulesDir(): File = StoragePaths.jsDir(appContext!!)

    private fun rulesDirOrNull(): File? {
        val ctx = appContext ?: return null
        return StoragePaths.jsDir(ctx)
    }

    /** 从规则源码里抠 title（@header 或 title: '...'），抠不到返回 null。 */
    private fun extractTitle(file: File): String? {
        return try {
            val head = file.inputStream().use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
            Regex("""title\s*:\s*['"]([^'"]{1,40})['"]""").find(head)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}
