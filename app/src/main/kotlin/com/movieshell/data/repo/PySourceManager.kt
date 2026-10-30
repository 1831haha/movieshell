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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren

/**
 * PY 规则源管理器（单例，UI 组按此契约调用）。
 *
 * - init(context)：主线程，幂等；首次把 assets/py/ 下的 .py 规则
 *   拷贝到 StoragePaths.pyDir()（/storage/emulated/0/xiaying/py/)。
 * - listRules()：扫描 StoragePaths.pyDir()（/storage/emulated/0/xiaying/py/) 下的 py 文件。
 * - setActiveRule(name)：主线程；null=清空；切换只换文件名，
 *   Python 解释器由 ChaquopyEngine 懒启动并全局复用。
 * - 无 active rule 时所有 suspend 函数返回空（不抛异常）。
 *
 * v0.22.0 并发（对标 DsPlayer 0.9.9"源忙"根除）：
 * - 读方法（home/categories/category/detail/search）在共享 readScope 里 async 执行，
 *   多个 UI 协程可并发；Python 侧 GIL 串行字节码，但 requests 等网络 I/O 会释放 GIL，
 *   并发请求的网络等待天然重叠。
 * - 同一读方法的新调用取消旧请求（切分类/切源立即生效，旧结果不再回写覆盖）。
 * - setActiveRule 时取消全部在途读请求。
 * - v0.187.0 H03 如实说明的固有限制：上述“取消”只取消协程等待，停不掉
 *   Chaquopy 桥上正在执行的 Python。桥调用是同步阻塞接口，不响应协程取消，
 *   被取消请求的 Python 仍会跑到结束、只占一条 IO 线程；其结果因 Deferred
 *   已取消而丢弃，不会回写覆盖。这不是本版能修掉的，是桥的固有限制，
 *   不要假装取消等于停掉 Python。
 * - play 保持直调（写敏感，不参与取消）。
 */
object PySourceManager {

    private const val TAG = "PySourceManager"
    private const val RULES_DIR = "py"

    /** PY 标题正则（class XxxSpider），抠标题只此一处（见 RuleFileStore）。 */
    private val TITLE_REGEX = Regex("""class\s+(\w+Spider)""")

    private var appContext: Context? = null
    private var initialized = false

    private var activeName: String? = null
    private val api: PyApi by lazy { PyApi() }

    /**
     * v0.217.0（F53）：与 DrpySourceManager 同款——本地 PY 读方法原来失败
     * 只记日志、交空结果，聚合搜索的失败桥接读不到原因，PY 站挂了也只
     * 算“没数据”；现在失败原因记进 managerLastError，适配器透传给桥接。
     */
    private var managerLastError: String? = null

    /** 引擎抛异常时记原因（日志仍由各调用点记，这里只留载体）。 */
    private fun recordFailure(e: Throwable) {
        managerLastError = e.message?.takeIf { it.isNotBlank() }
            ?: e::class.java.simpleName
    }

    /** 适配器透传：最近一次失败原因；无激活规则时明确报无规则。 */
    fun adapterLastError(): String? =
        managerLastError ?: if (activeName == null) "无激活规则" else null

    /** v0.22.0：读请求共享域；SupervisorJob 保证单个请求失败不牵连其他。 */
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 每个读方法只保留最新请求；新调用取消旧的（切分类/切源立即生效）。 */
    private val homeSlot = AtomicReference<Deferred<List<Vod>>?>(null)
    private val categoriesSlot = AtomicReference<Deferred<List<CategoryType>>?>(null)
    private val categorySlot = AtomicReference<Deferred<List<Vod>>?>(null)
    private val detailSlot = AtomicReference<Deferred<VodDetail>?>(null)
    private val searchSlot = AtomicReference<Deferred<List<Vod>>?>(null)

    /**
     * 最新者胜：取消该读方法的旧在途请求，执行新请求并等待。
     * 旧调用方收到 CancellationException（协程标准取消语义）。
     * v0.187.0 H03 如实说明：这里的取消只取消协程等待，停不掉桥上正在
     * 执行的 Python（同步阻塞接口不响应取消）；旧 Python 跑完的结果被丢弃，不回写。
     */
    /**
     * v0.218.0（S24）：槽位更新原子化——原来“取旧、建新、存新”三步，
     * 交错时后建的请求可能被先建的请求覆盖槽位、取消信号发错对象；
     * 现在先建 deferred 再一次 getAndSet 完成“取旧＋存新”。
     */
    private suspend fun <T> latest(slot: AtomicReference<Deferred<T>?>, block: suspend () -> T): T {
        val d = readScope.async { block() }
        slot.getAndSet(d)?.cancel()
        return d.await() // 被更新的请求取消时向调用方抛 CancellationException（标准语义）
    }

    /** init：主线程，幂等；首次把 assets/py/ 下规则拷贝到 StoragePaths.pyDir()（/storage/emulated/0/xiaying/py/)。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        ChaquopyEngine.init(context)
        RuleFileStore.copyBuiltinAssets(appContext!!.assets, RULES_DIR, rulesDir(), ".py", emptySet(), TAG)
    }

    /** 扫描 StoragePaths.pyDir()（/storage/emulated/0/xiaying/py/) 下的 py 文件。 */
    fun listRules(): List<RuleInfo> = RuleFileStore.listRules(
        rulesDirOrNull(),
        { f -> f.isFile && f.name.endsWith(".py") },
        { f -> RuleFileStore.extractTitle(f, TITLE_REGEX) },
    )

    fun activeRuleName(): String? = activeName

    /** v0.291.0：proxy:// 封面取图用——返回当前激活 PY 规则的可信副本路径（与调规则同一路径）。 */
    fun activeRulePathForProxy(): String? = activeRulePath()

    fun hasActiveRule(): Boolean = activeName != null

    /** 切换 active 规则。主线程调用；name=null 时清空。v0.22.0：切换时取消全部在途读请求。v0.187.0 H03：取消只取消等待，停不掉桥上正在执行的 Python，见类注释固有限制。 */
    /** v0.215.0：同 drpy——先验文件存在再动当前态，返回是否切源成功。 */
    fun setActiveRule(name: String?): Boolean {
        if (name == activeName) return true
        if (name != null) {
            val probe = File(rulesDirOrNull() ?: return false, name)
            if (!probe.isFile) {
                Log.w(TAG, "规则文件不存在: $name")
                return false
            }
        }
        readScope.coroutineContext[Job]?.cancelChildren()
        activeName = null
        if (name == null) return true
        val file = File(rulesDirOrNull() ?: return false, name)
        if (!file.isFile) {
            Log.w(TAG, "规则文件不存在: $name")
            return false
        }
        // S2：显式切源重新信任，之后 activeRulePath 一律读可信副本。
        val ctx = appContext
        if (ctx != null) {
            ExecutableTrust.trustedFile(ctx.filesDir, file, "py:$name", allowRetrust = true)
        }
        activeName = name
        Log.i(TAG, "active PY 规则: $name")
        return true
    }

    private fun activeRulePath(): String? {
        val dir = rulesDirOrNull() ?: return null
        val name = activeName ?: return null
        val ctx = appContext ?: return null
        // S2：后台执行读私有可信副本；公开文件被改写后返回旧副本，
        // 可信副本也不可信时返回 null，调用方按无规则处理，不读公开文件。
        val trusted = ExecutableTrust.trustedFile(
            ctx.filesDir,
            File(dir, name),
            "py:$name",
            allowRetrust = false,
        ) ?: return null
        return trusted.absolutePath
    }

    suspend fun home(): List<Vod> {
        val path = activeRulePath() ?: return emptyList()
        return latest(homeSlot) {
            runCatching { api.home(path) }
                .onFailure { e ->
                    recordFailure(e)
                    LogBus.e(TAG, "本地 PY home 失败: ${e::class.simpleName}: ${e.message}")
                }
                .onSuccess { managerLastError = null }
                .getOrNull().orEmpty().filterNotNull()
        }
    }

    suspend fun categories(): List<CategoryType> {
        val path = activeRulePath() ?: return emptyList()
        return latest(categoriesSlot) {
            runCatching { api.categories(path) }
                .onFailure { e ->
                    recordFailure(e)
                    LogBus.e(TAG, "本地 PY categories 失败: ${e::class.simpleName}: ${e.message}")
                }
                .onSuccess { managerLastError = null }
                .getOrNull().orEmpty().filterNotNull()
        }
    }

    suspend fun category(tid: String, pg: Int, filter: Map<String, String> = emptyMap()): List<Vod> {
        val path = activeRulePath() ?: return emptyList()
        return latest(categorySlot) {
            runCatching { api.category(path, tid, pg, filter) }
                .onFailure { e ->
                    recordFailure(e)
                    LogBus.e(TAG, "本地 PY category 失败: ${e::class.simpleName}: ${e.message}")
                }
                .onSuccess { managerLastError = null }
                .getOrNull().orEmpty().filterNotNull()
        }
    }

    suspend fun detail(vodId: String): VodDetail {
        val path = activeRulePath() ?: return VodDetail(vodId = vodId)
        return latest(detailSlot) {
            runCatching { api.detail(path, vodId) }
                .onFailure { e ->
                    recordFailure(e)
                    LogBus.e(TAG, "本地 PY detail 失败: ${e::class.simpleName}: ${e.message}")
                }
                .onSuccess { managerLastError = null }
                .getOrNull() ?: VodDetail(vodId = vodId)
        }
    }

    suspend fun search(wd: String): List<Vod> {
        val path = activeRulePath() ?: return emptyList()
        return latest(searchSlot) {
            runCatching { api.search(path, wd) }
                .onFailure { e ->
                    recordFailure(e)
                    LogBus.e(TAG, "本地 PY search 失败: ${e::class.simpleName}: ${e.message}")
                }
                .onSuccess { managerLastError = null }
                .getOrNull().orEmpty().filterNotNull()
        }
    }

    suspend fun playUrl(flag: String, id: String): String {
        // v0.188.0 M14：此前失败时 getOrNull() 直接交空串、无任何日志，
        // 排障时分不清是规则没给地址还是执行失败。失败记规则路径 + 原因。
        // 无激活规则时仍静默交空（不是一次失败，调用方按无规则处理）。
        val path = activeRulePath() ?: return ""
        return runCatching { api.play(path, flag, id).first }
            .onFailure { e ->
                recordFailure(e)
                LogBus.e(TAG, "本地 PY play 失败：规则=$path flag=$flag 原因=${e.message ?: e::class.java.simpleName}")
            }
            .onSuccess { managerLastError = null }
            .getOrNull().orEmpty()
    }

    /** v0.27.1：本地 PY 播放原始结果（url, parse, header），供 PlayResolver 做收尾。 */
    suspend fun playRaw(flag: String, id: String): Triple<String, Int, Map<String, String>> {
        // v0.188.0 M14：与 playUrl 同款，失败记规则路径 + 原因，不再静默交空 Triple。
        val path = activeRulePath() ?: return Triple("", 0, emptyMap())
        return runCatching { api.play(path, flag, id) }
            .onFailure { e ->
                recordFailure(e)
                LogBus.e(TAG, "本地 PY playRaw 失败：规则=$path flag=$flag 原因=${e.message ?: e::class.java.simpleName}")
            }
            .onSuccess { managerLastError = null }
            .getOrNull() ?: Triple("", 0, emptyMap())
    }

    /**
     * 给 SiteRepository 构造器用的 DrpyEngine 适配器：
     * 委托给当前 active 的 PY 规则；无 active 规则时返回空结果。
     */
    val engineAdapter: DrpyEngine = object : DrpyEngine {
            /** v0.217.0（F53）：透传最近一次失败原因（drpy 适配器同款）。 */
            override val lastError: String?
                get() = this@PySourceManager.adapterLastError()

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

    private fun rulesDir(): File = StoragePaths.pyDir(appContext!!)

    private fun rulesDirOrNull(): File? {
        val ctx = appContext ?: return null
        return StoragePaths.pyDir(ctx)
    }

    /** 是否含 Spider 子类（桥接/工具类 py 文件没有，不计入检测）。 */
    fun hasSpiderSubclass(name: String): Boolean {
        val ctx = appContext ?: return false
        return RuleFileStore.extractTitle(File(StoragePaths.pyDir(ctx), name), TITLE_REGEX) != null
    }

}
