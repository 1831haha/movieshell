package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.source.DrpyEngine
import com.movieshell.data.source.V8DrpyEngine
import java.io.File

/**
 * drpy 规则源管理器（单例，UI 组按此契约调用）。
 *
 * - init(context)：主线程，幂等；首次把 assets/drpy/ 下的规则（除垫片）
 *   拷贝到 StoragePaths.jsDir()（/storage/emulated/0/xiaying/js/)。
 * - listRules()：扫描 StoragePaths.jsDir()（/storage/emulated/0/xiaying/js/) 下的 js 文件。
 * - setActiveRule(name)：主线程；null=清空；切换时为该规则创建/复用
 *   V8DrpyEngine 并 loadRule。
 * - 无 active rule 时所有 suspend 函数返回空（不抛异常）。
 * - engine：给 SiteRepository 用的 DrpyEngine 适配器，委托给当前 active 引擎。
 */
object DrpySourceManager {

    private const val TAG = "DrpySourceManager"
    private const val RULES_DIR = "drpy"
    private const val SHIM_NAME = "shim.js"

    /** drpy 标题正则（title: '...'），抠标题只此一处（见 RuleFileStore）。 */
    private val TITLE_REGEX = Regex("""title\s*:\s*['"]([^'"]{1,40})['"]""")

    private var appContext: Context? = null
    private var initialized = false

    @Volatile
    private var engine: V8DrpyEngine? = null
    @Volatile
    private var activeName: String? = null

    /**
     * v0.188.0 M13：换规则世代号。setActiveRule 先 bump 世代、再 release
     * 旧引擎；在途调用返回时见世代已变或引擎已不是当初那一个，即丢弃
     * 结果交空，不再把旧规则数据写回 UI、也不再按旧结果清/记失败原因。
     * 取消语义说明：这里只丢弃结果，不中断引擎内已在跑的 JS——release
     * 本身即时终止 isolate，在途 eval 会以关闭异常快速失败；世代号兜底
     * 的是“恰好在 release 前已拿到结果、返回时规则已换”的窗口。
     */
    @Volatile
    private var generation = 0

    /**
     * v0.187.0 H04：管理器层捕获到的失败原因（引擎抛异常时记，成功时清）。
     * 引擎内部失败走引擎自身的 lastError；两者互补，适配器先读引擎、再读这里。
     */
    @Volatile
    private var managerLastError: String? = null

    /**
     * v0.187.0 H04：适配器读失败原因的唯一入口（适配器是嵌套对象表达式，
     * 直接读外层私有字段不可靠，走公开函数最稳）。
     */
    fun adapterLastError(): String? =
        engine?.lastError ?: managerLastError ?: if (engine == null) "无激活规则" else null

    /** 引擎抛异常时把原因记下来进 LogBus，不再静默吞成空结果。 */
    private fun recordFailure(op: String, e: Throwable) {
        val reason = engine?.lastError
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: e::class.java.simpleName
        managerLastError = reason
        LogBus.e(TAG, "drpy $op 失败：$reason")
    }

    /** init：主线程，幂等；首次把 assets/drpy/ 下规则拷贝到 StoragePaths.jsDir()（/storage/emulated/0/xiaying/js/)。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        RuleFileStore.copyBuiltinAssets(appContext!!.assets, RULES_DIR, rulesDir(), ".js", setOf(SHIM_NAME), TAG)
    }

    /** 扫描 StoragePaths.jsDir()（/storage/emulated/0/xiaying/js/) 下的 js 文件。 */
    fun listRules(): List<RuleInfo> = RuleFileStore.listRules(
        rulesDirOrNull(),
        { f -> f.isFile && f.name.endsWith(".js") && f.name != SHIM_NAME },
        { f -> RuleFileStore.extractTitle(f, TITLE_REGEX) },
    )

    fun activeRuleName(): String? = activeName

    fun hasActiveRule(): Boolean = engine != null && activeName != null

    /**
     * 切换 active 规则。主线程调用；name=null 时清空。
     * 同名重复调用直接复用；切换时先 bump 世代号（在途调用随后按旧结果丢弃）、
     * 再销毁旧引擎、为新规则建 V8DrpyEngine 并 loadRule。
     */
    /**
     * v0.215.0：返回是否切源成功——先验规则文件存在再动当前态。
     * 原来先 release 旧引擎、置空 activeName 才验文件，继续观看点到
     * 已删源时直接把当前好源毁掉（activeName=null、引擎已放）。
     */
    fun setActiveRule(name: String?): Boolean {
        if (name == activeName) return true
        if (name != null) {
            val probe = File(rulesDirOrNull() ?: return false, name)
            if (!probe.isFile) {
                Log.w(TAG, "规则文件不存在: $name")
                return false
            }
        }
        generation++
        try {
            engine?.release()
        } catch (e: Exception) {
            Log.w(TAG, "旧引擎 release 异常", e)
        }
        engine = null
        activeName = null
        if (name == null) return true
        val file = File(rulesDirOrNull() ?: return false, name)
        if (!file.isFile) {
            Log.w(TAG, "规则文件不存在: $name")
            return false
        }
        return try {
            val js = file.readText()
            val ctx = appContext ?: return false
            // v0.112.0 D2：删重复 release——旧引擎已在函数开头 try/catch 里释放并置空，
            // 此处的 engine?.release() 恒为 no-op。保留开头那一次。
            engine = V8DrpyEngine(ctx).also { it.loadRule(js) }
            activeName = name
            Log.i(TAG, "active 规则: $name")
            true
        } catch (e: Exception) {
            Log.w(TAG, "载入规则失败: $name", e)
            false
        }
    }

    /**
     * 世代守门：快照调用开始时的引擎与世代号；返回时任一对不上即结果作废。
     * 注意失败/成功记账只对未作废的调用做，旧规则的失败不污染新规则的原因槽。
     */
    private fun isStale(gen: Int, eng: V8DrpyEngine?): Boolean =
        gen != generation || engine !== eng

    suspend fun home(): List<Vod> {
        val eng = engine
        val gen = generation
        val outcome = runCatching { eng?.home() }
        if (isStale(gen, eng)) return emptyList()
        return outcome
            .onFailure { recordFailure("home", it) }
            .onSuccess { managerLastError = null }
            .getOrNull().orEmpty()
    }

    suspend fun categories(): List<CategoryType> {
        val eng = engine
        val gen = generation
        val outcome = runCatching { eng?.categories() }
        if (isStale(gen, eng)) return emptyList()
        return outcome
            .onFailure { recordFailure("categories", it) }
            .onSuccess { managerLastError = null }
            .getOrNull().orEmpty()
    }

    suspend fun category(tid: String, pg: Int, filter: Map<String, String> = emptyMap()): List<Vod> {
        val eng = engine
        val gen = generation
        val outcome = runCatching { eng?.category(tid, pg, filter) }
        if (isStale(gen, eng)) return emptyList()
        return outcome
            .onFailure { recordFailure("category", it) }
            .onSuccess { managerLastError = null }
            .getOrNull().orEmpty()
    }

    /** 当前规则某分类的筛选定义（无筛选时空列表）。 */
    suspend fun filters(tid: String): List<FilterGroup> {
        val eng = engine
        val gen = generation
        val outcome = runCatching { eng?.filters(tid) }
        if (isStale(gen, eng)) return emptyList()
        return outcome
            .onFailure { recordFailure("filters", it) }
            .onSuccess { managerLastError = null }
            .getOrNull().orEmpty()
    }

    suspend fun detail(vodId: String): VodDetail {
        val eng = engine
        val gen = generation
        val outcome = runCatching { eng?.detail(vodId) }
        if (isStale(gen, eng)) return VodDetail(vodId = vodId)
        return outcome
            .onFailure { recordFailure("detail", it) }
            .onSuccess { managerLastError = null }
            .getOrNull() ?: VodDetail(vodId = vodId)
    }

    suspend fun search(wd: String): List<Vod> {
        val eng = engine
        val gen = generation
        val outcome = runCatching { eng?.search(wd) }
        if (isStale(gen, eng)) return emptyList()
        return outcome
            .onFailure { recordFailure("search", it) }
            .onSuccess { managerLastError = null }
            .getOrNull().orEmpty()
    }

    suspend fun playUrl(flag: String, id: String): String {
        val eng = engine
        val gen = generation
        val outcome = runCatching { eng?.play(flag, id) }
        if (isStale(gen, eng)) return ""
        return outcome
            .onFailure { recordFailure("play", it) }
            .onSuccess { managerLastError = null }
            .getOrNull().orEmpty()
    }

    /**
     * 给 SiteRepository 构造器用的 DrpyEngine 适配器：
     * 委托给当前 active 规则的引擎；无 active 规则时返回空结果。
     */
    val drpyEngineAdapter: DrpyEngine = object : DrpyEngine {
        /**
         * v0.187.0 H04：透传当前引擎的失败原因；引擎为空时说明无激活规则，
         * 空结果不是“源没数据”而是“没选规则”，调用方据此区分。
         */
        override val lastError: String?
            get() = this@DrpySourceManager.adapterLastError()

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

}
