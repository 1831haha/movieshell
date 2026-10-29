package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.model.isDrpySite
import com.movieshell.data.model.isPySite
import com.movieshell.data.model.isSubSite
import com.movieshell.data.source.ChaquopyEngine
import com.movieshell.data.source.DrpyEngine
import com.movieshell.data.source.PyApi
import com.movieshell.data.source.RuleImportExpander
import com.movieshell.data.source.V8DrpyEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 订阅站源的按站引擎（v0.5.1）。
 *
 * SiteRepository 的 drpyEngine/pyEngine 构造参数只绑定"当前激活的本地规则"，
 * 订阅展开的子站点（key 前缀 "sub:"）各自有规则文件，需要按站取引擎：
 *
 * - drpy 站：规则文件在 SubSiteStore 展开时已下载到 filesDir/drpy/subs/，
 *   site.api 即本地绝对路径。用 SubDrpyPool 做引擎池（最多 3 个 WebView，
 *   LRU 淘汰），并发搜索时按站复用引擎、串行 loadRule 竞争由信号量隔离。
 * - PY 站：PyApi 本来就是无状态的（方法都带 rulePath），直接按站构造适配器。
 * - 非订阅站返回 null，调用方回退到构造时传入的默认引擎。
 * - 订阅站但规则文件丢失：返回 EmptyDrpyEngine（全空结果），避免误用别的站的引擎。
 */
object SubSiteEngines {
    private const val TAG = "SubSiteEngines"

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    internal fun requireContext(): Context =
        appContext ?: throw IllegalStateException("SubSiteEngines 未 init")

    /**
     * 按站取 drpy 引擎：订阅站（sub:）与本地规则站（local:drpy:，v0.190.0
     * 聚合搜索逐条搜本地规则用）都按 api 里的规则文件取池化引擎；
     * 其他站 null（调用方回退到当前激活规则引擎）；文件丢失 -> 空引擎，
     * 避免误用别的站的引擎。
     */
    fun drpyEngineFor(site: Site): DrpyEngine? {
        val isLocal = site.key.startsWith("local:drpy:")
        if (!isLocal && !site.isSubSite()) return null
        // 本地站 type=4 会撞 type4 判定（isDrpySite 排除 type=4），
        // 这里只认 api 是否 .js 结尾；订阅站仍走 isDrpySite 全套判定。
        val isDrpyApi = if (isLocal) {
            site.api.trim().endsWith(".js", ignoreCase = true)
        } else {
            site.isDrpySite()
        }
        if (!isDrpyApi) return null
        val f = File(site.api)
        if (!f.isFile) {
            Log.w(TAG, "drpy 站规则文件丢失: ${site.key}")
            return EmptyDrpyEngine
        }
        // S2：后台执行不读公开文件，读私有可信副本；公开文件被改写
        // 后返回旧副本（见 ExecutableTrust），可信副本也不可信时拒绝。
        val trusted = ExecutableTrust.trustedFile(
            requireContext().filesDir,
            f,
            "site:${site.key}",
            allowRetrust = false,
        ) ?: run {
            Log.w(TAG, "drpy 站规则信任校验失败，拒绝执行: ${site.key}")
            return EmptyDrpyEngine
        }
        return PooledDrpyEngine(site.key, trusted)
    }

    /**
     * 按站取 PY 引擎：订阅站（sub:）与本地规则站（local:py:，v0.190.0
     * 聚合搜索逐条搜本地规则用）都按 api 里的规则文件取适配器；
     * 其他站 null（调用方回退到当前激活规则引擎）；文件丢失 -> 空引擎。
     */
    fun pyEngineFor(site: Site): DrpyEngine? {
        val isLocal = site.key.startsWith("local:py:")
        if (!isLocal && !site.isSubSite()) return null
        if (!site.isPySite()) return null
        val f = File(site.api)
        if (!f.isFile) {
            Log.w(TAG, "PY 站规则文件丢失: ${site.key}")
            return EmptyDrpyEngine
        }
        // S2：与 drpy 同款，后台执行读私有可信副本，不读公开文件。
        val trusted = ExecutableTrust.trustedFile(
            requireContext().filesDir,
            f,
            "site:${site.key}",
            allowRetrust = false,
        ) ?: run {
            Log.w(TAG, "PY 站规则信任校验失败，拒绝执行: ${site.key}")
            return EmptyDrpyEngine
        }
        return SubPyEngine(trusted.absolutePath)
    }

    /** v0.27.1：订阅 PY 站播放原始结果（url, parse, header），供 PlayResolver 做收尾。 */
    suspend fun pyPlayRaw(site: Site, flag: String, id: String): Triple<String, Int, Map<String, String>> {
        if (!site.isSubSite() || !site.isPySite()) return Triple("", 0, emptyMap())
        val f = File(site.api)
        if (!f.isFile) return Triple("", 0, emptyMap())
        // S2：播放同样走可信副本，不读公开文件。
        val trusted = runCatching {
            ExecutableTrust.trustedFile(
                requireContext().filesDir,
                f,
                "site:${site.key}",
                allowRetrust = false,
            )
        }.getOrNull() ?: return Triple("", 0, emptyMap())
        return runCatching { PyApi(ChaquopyEngine).play(trusted.absolutePath, flag, id) }
            .getOrDefault(Triple("", 0, emptyMap()))
    }
}

/** 详情页站点上下文：从订阅站源点进详情时由 Home/Search/Favorites 写入，DetailScreen 读取。 */
object DetailSiteHolder {
    var site: Site? = null
}

/** 空引擎：规则文件丢失等异常情况用，全空结果，不抛异常。 */
private object EmptyDrpyEngine : DrpyEngine {
    override fun loadRule(js: String) {}
    override suspend fun home(): List<Vod> = emptyList()
    override suspend fun categories(): List<CategoryType> = emptyList()
    override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> = emptyList()
    override suspend fun detail(id: String): VodDetail = VodDetail(vodId = id)
    override suspend fun search(wd: String): List<Vod> = emptyList()
    override suspend fun play(flag: String, id: String): String = ""
    override fun release() {}
}

/**
 * 订阅 drpy 站的引擎适配器：每次调用都经 SubDrpyPool 拿到该站的
 * V8DrpyEngine（已载入该站规则）再执行；池负责复用与 LRU 淘汰。
 */
private class PooledDrpyEngine(
    private val siteKey: String,
    private val ruleFile: File,
) : DrpyEngine {
    // v0.217.0（F53）：透传池内引擎的失败原因——原来适配器没覆写
    // lastError，订阅 drpy 站失败时桥接读到的恒为 null，失败统计里
    // 订阅站永远算“没数据”；每次调用后把内层引擎的原因记下来。
    @Volatile
    private var lastSeenError: String? = null
    override val lastError: String? get() = lastSeenError

    override fun loadRule(js: String) {
        // 规则按站预载，不支持外部直接 load
    }

    override suspend fun home(): List<Vod> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { eng ->
            eng.home().also { lastSeenError = eng.lastError }
        }

    override suspend fun categories(): List<CategoryType> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { eng ->
            eng.categories().also { lastSeenError = eng.lastError }
        }

    override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { eng ->
            eng.category(tid, pg, filter).also { lastSeenError = eng.lastError }
        }

    override suspend fun filters(tid: String): List<FilterGroup> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { eng ->
            eng.filters(tid).also { lastSeenError = eng.lastError }
        }

    override suspend fun detail(id: String): VodDetail =
        SubDrpyPool.withEngine(siteKey, ruleFile) { eng ->
            eng.detail(id).also { lastSeenError = eng.lastError }
        }

    override suspend fun search(wd: String): List<Vod> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { eng ->
            eng.search(wd).also { lastSeenError = eng.lastError }
        }

    override suspend fun play(flag: String, id: String): String =
        SubDrpyPool.withEngine(siteKey, ruleFile) { eng ->
            eng.play(flag, id).also { lastSeenError = eng.lastError }
        }

    override fun release() {
        // 生命周期归池管理
    }
}

/**
 * drpy 引擎池：订阅站多、V8 isolate 不共享线程，池上限 3 个，按最近使用淘汰。
 * 两个上限含义不同，不可混淆：
 * - Semaphore 限的是并发执行的调用数（block 在途数），不是引擎数；
 * - 引擎数上限是 MAX_ENGINES，由 engines 表大小约束。
 * 淘汰前必查在途计数：在途不为 0 的引擎绝不 release；若全部在途，宁可暂超
 * 引擎上限建一个新引擎，也不关掉正在执行的引擎。
 */
private object SubDrpyPool {
    private const val TAG = "SubDrpyPool"
    private const val MAX_ENGINES = 3

    private val mutex = Mutex()
    private val semaphore = Semaphore(MAX_ENGINES)

    /** 引擎 + 在途调用计数：取用时加一、block 结束时减一，减一用原子操作，取消时也能执行。 */
    private class PooledEntry(val engine: V8DrpyEngine) {
        val inFlight = AtomicInteger(0)
    }

    /** LinkedHashMap 插入序：每次取用后 remove 再 put，队首即最久未用。 */
    private val engines = LinkedHashMap<String, PooledEntry>()

    suspend fun <T> withEngine(
        siteKey: String,
        ruleFile: File,
        block: suspend (DrpyEngine) -> T,
    ): T {
        semaphore.acquire()
        var entry: PooledEntry? = null
        try {
            entry = mutex.withLock {
                val cached = engines.remove(siteKey)
                val e = cached ?: run {
                    if (engines.size >= MAX_ENGINES) {
                        val victim = engines.entries.firstOrNull { it.value.inFlight.get() == 0 }
                        if (victim != null) {
                            engines.remove(victim.key)
                            runCatching { victim.value.engine.release() }
                            Log.i(TAG, "淘汰引擎: ${victim.key}")
                        } else {
                            Log.w(TAG, "引擎池已满且全部在途，暂超上限建新引擎（不淘汰在途引擎）")
                        }
                    }
                    PooledEntry(
                        V8DrpyEngine(SubSiteEngines.requireContext()).also { eng ->
                            // S2：规则正文已是可信副本，import 也从可信副本展开，
                            // 不再让引擎回公开 js 目录读 import 文件。展开后已无
                            // import 语句，引擎自己的公开目录展开是空操作。
                            val ctx = SubSiteEngines.requireContext()
                            val raw = ruleFile.readText()
                            val expanded = RuleImportExpander.expand(raw) { spec ->
                                runCatching {
                                    val dir = StoragePaths.jsDir(ctx)
                                    val lib = File(dir, spec)
                                    if (!lib.canonicalPath.startsWith(dir.canonicalPath)) null
                                    else {
                                        val rel = lib.relativeTo(dir).path.removePrefix("./")
                                        val trustedLib = ExecutableTrust.trustedFile(
                                            ctx.filesDir,
                                            lib,
                                            "js:$rel",
                                            allowRetrust = false,
                                        ) ?: return@runCatching null
                                        trustedLib.readText()
                                    }
                                }.getOrNull()
                            }
                            eng.loadRule(expanded.js)
                        },
                    )
                }
                engines[siteKey] = e
                e.inFlight.incrementAndGet()
                e
            }
            return block(entry.engine)
        } finally {
            entry?.inFlight?.decrementAndGet()
            semaphore.release()
        }
    }
}

/** 订阅 PY 站的引擎适配器：PyApi 无状态，按 rulePath 直接调。
 * v0.217.0（F53）：与 PooledDrpyEngine 同款——原来失败只记日志、
 * 原因没载体，桥接读到的恒为 null；现在失败原因记进 lastSeenError。 */
private class SubPyEngine(private val rulePath: String) : DrpyEngine {
    private val api: PyApi by lazy { PyApi(ChaquopyEngine) }

    @Volatile
    private var lastSeenError: String? = null
    override val lastError: String? get() = lastSeenError

    private fun recordFailure(e: Throwable) {
        lastSeenError = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
    }

    companion object {
        private const val TAG = "SubPyEngine"
    }

    override fun loadRule(js: String) {}

    override suspend fun home(): List<Vod> =
        runCatching { api.home(rulePath) }
            .onFailure {
                recordFailure(it)
                LogBus.e(TAG, "PY home 失败 $rulePath: ${it.message}")
            }
            .onSuccess { lastSeenError = null }
            .getOrNull().orEmpty()

    override suspend fun categories(): List<CategoryType> =
        runCatching { api.categories(rulePath) }
            .onFailure {
                recordFailure(it)
                LogBus.e(TAG, "PY categories 失败 $rulePath: ${it.message}")
            }
            .onSuccess { lastSeenError = null }
            .getOrNull().orEmpty()

    override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> =
        runCatching { api.category(rulePath, tid, pg) }
            .onFailure {
                recordFailure(it)
                LogBus.e(TAG, "PY category 失败 $rulePath: ${it.message}")
            }
            .onSuccess { lastSeenError = null }
            .getOrNull().orEmpty()

    override suspend fun detail(id: String): VodDetail =
        runCatching { api.detail(rulePath, id) }
            .onFailure {
                recordFailure(it)
                LogBus.e(TAG, "PY detail 失败 $rulePath: ${it.message}")
            }
            .onSuccess { lastSeenError = null }
            .getOrNull() ?: VodDetail(vodId = id)

    override suspend fun search(wd: String): List<Vod> =
        runCatching { api.search(rulePath, wd) }
            .onFailure {
                recordFailure(it)
                LogBus.e(TAG, "PY search 失败 $rulePath: ${it.message}")
            }
            .onSuccess { lastSeenError = null }
            .getOrNull().orEmpty()

    override suspend fun play(flag: String, id: String): String =
        runCatching { api.play(rulePath, flag, id).first }
            .onFailure { recordFailure(it) }
            .onSuccess { lastSeenError = null }
            .getOrNull().orEmpty()

    override fun release() {}
}
