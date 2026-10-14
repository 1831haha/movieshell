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
 * - play 保持直调（写敏感，不参与取消）。
 */
object PySourceManager {

    data class RuleInfo(val name: String, val title: String, val sizeKb: Long)

    private const val TAG = "PySourceManager"
    private const val RULES_DIR = "py"

    private var appContext: Context? = null
    private var initialized = false

    private var activeName: String? = null
    private val api: PyApi by lazy { PyApi() }

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
     */
    private suspend fun <T> latest(slot: AtomicReference<Deferred<T>?>, block: suspend () -> T): T {
        slot.getAndSet(null)?.cancel()
        val d = readScope.async { block() }
        slot.set(d)
        return d.await() // 被更新的请求取消时向调用方抛 CancellationException（标准语义）
    }

    /** init：主线程，幂等；首次把 assets/py/ 下规则拷贝到 StoragePaths.pyDir()（/storage/emulated/0/xiaying/py/)。 */
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

    /** 扫描 StoragePaths.pyDir()（/storage/emulated/0/xiaying/py/) 下的 py 文件。 */
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

    /** 切换 active 规则。主线程调用；name=null 时清空。v0.22.0：切换时取消全部在途读请求。 */
    fun setActiveRule(name: String?) {
        if (name == activeName) return
        readScope.coroutineContext[Job]?.cancelChildren()
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

    suspend fun home(): List<Vod> {
        val path = activeRulePath() ?: return emptyList()
        return latest(homeSlot) {
            runCatching { api.home(path) }
                .onFailure { e -> LogBus.e(TAG, "本地 PY home 失败: ${e.message}") }
                .getOrNull().orEmpty().filterNotNull()
        }
    }

    suspend fun categories(): List<CategoryType> {
        val path = activeRulePath() ?: return emptyList()
        return latest(categoriesSlot) {
            runCatching { api.categories(path) }
                .onFailure { e -> LogBus.e(TAG, "本地 PY categories 失败: ${e.message}") }
                .getOrNull().orEmpty().filterNotNull()
        }
    }

    suspend fun category(tid: String, pg: Int, filter: Map<String, String> = emptyMap()): List<Vod> {
        val path = activeRulePath() ?: return emptyList()
        return latest(categorySlot) {
            runCatching { api.category(path, tid, pg) }
                .onFailure { e -> LogBus.e(TAG, "本地 PY category 失败: ${e.message}") }
                .getOrNull().orEmpty().filterNotNull()
        }
    }

    suspend fun detail(vodId: String): VodDetail {
        val path = activeRulePath() ?: return VodDetail(vodId = vodId)
        return latest(detailSlot) {
            runCatching { api.detail(path, vodId) }
                .onFailure { e -> LogBus.e(TAG, "本地 PY detail 失败: ${e.message}") }
                .getOrNull() ?: VodDetail(vodId = vodId)
        }
    }

    suspend fun search(wd: String): List<Vod> {
        val path = activeRulePath() ?: return emptyList()
        return latest(searchSlot) {
            runCatching { api.search(path, wd) }
                .onFailure { e -> LogBus.e(TAG, "本地 PY search 失败: ${e.message}") }
                .getOrNull().orEmpty().filterNotNull()
        }
    }

    suspend fun playUrl(flag: String, id: String): String =
        activeRulePath()?.let { runCatching { api.play(it, flag, id).first }.getOrNull() }
            .orEmpty()

    /** v0.27.1：本地 PY 播放原始结果（url, parse, header），供 PlayResolver 做收尾。 */
    suspend fun playRaw(flag: String, id: String): Triple<String, Int, Map<String, String>> =
        activeRulePath()?.let { p -> runCatching { api.play(p, flag, id) }.getOrNull() }
            ?: Triple("", 0, emptyMap())

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

    private fun rulesDir(): File = StoragePaths.pyDir(appContext!!)

    private fun rulesDirOrNull(): File? {
        val ctx = appContext ?: return null
        return StoragePaths.pyDir(ctx)
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
