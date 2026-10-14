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
import com.movieshell.data.source.QuickJsDrpyEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.File

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

    /** 订阅 drpy 站 -> 专属引擎；非订阅站 null；文件丢失 -> 空引擎。 */
    fun drpyEngineFor(site: Site): DrpyEngine? {
        if (!site.isSubSite()) return null
        if (!site.isDrpySite()) return null
        val f = File(site.api)
        if (!f.isFile) {
            Log.w(TAG, "订阅 drpy 站规则文件丢失: ${site.key}")
            return EmptyDrpyEngine
        }
        return PooledDrpyEngine(site.key, f)
    }

    /** 订阅 PY 站 -> 专属引擎；非订阅站 null；文件丢失 -> 空引擎。 */
    fun pyEngineFor(site: Site): DrpyEngine? {
        if (!site.isSubSite()) return null
        if (!site.isPySite()) return null
        val f = File(site.api)
        if (!f.isFile) {
            Log.w(TAG, "订阅 PY 站规则文件丢失: ${site.key}")
            return EmptyDrpyEngine
        }
        return SubPyEngine(f.absolutePath)
    }

    /** v0.27.1：订阅 PY 站播放原始结果（url, parse, header），供 PlayResolver 做收尾。 */
    suspend fun pyPlayRaw(site: Site, flag: String, id: String): Triple<String, Int, Map<String, String>> {
        if (!site.isSubSite() || !site.isPySite()) return Triple("", 0, emptyMap())
        val f = File(site.api)
        if (!f.isFile) return Triple("", 0, emptyMap())
        return runCatching { PyApi(ChaquopyEngine).play(f.absolutePath, flag, id) }
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
 * QuickJsDrpyEngine（已载入该站规则）再执行；池负责复用与 LRU 淘汰。
 */
private class PooledDrpyEngine(
    private val siteKey: String,
    private val ruleFile: File,
) : DrpyEngine {
    override fun loadRule(js: String) {
        // 规则按站预载，不支持外部直接 load
    }

    override suspend fun home(): List<Vod> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { it.home() }

    override suspend fun categories(): List<CategoryType> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { it.categories() }

    override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { it.category(tid, pg, filter) }

    override suspend fun filters(tid: String): List<FilterGroup> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { it.filters(tid) }

    override suspend fun detail(id: String): VodDetail =
        SubDrpyPool.withEngine(siteKey, ruleFile) { it.detail(id) }

    override suspend fun search(wd: String): List<Vod> =
        SubDrpyPool.withEngine(siteKey, ruleFile) { it.search(wd) }

    override suspend fun play(flag: String, id: String): String =
        SubDrpyPool.withEngine(siteKey, ruleFile) { it.play(flag, id) }

    override fun release() {
        // 生命周期归池管理
    }
}

/**
 * drpy 引擎池：订阅站多、WebView 重，池上限 3 个，按最近使用淘汰。
 * Semaphore 保证同时最多 3 个站并发执行，避免 WebView 爆炸。
 */
private object SubDrpyPool {
    private const val TAG = "SubDrpyPool"
    private const val MAX_ENGINES = 3

    private val mutex = Mutex()
    private val semaphore = Semaphore(MAX_ENGINES)

    /** LinkedHashMap 插入序：每次取用后 remove 再 put，队首即最久未用。 */
    private val engines = LinkedHashMap<String, QuickJsDrpyEngine>()

    suspend fun <T> withEngine(
        siteKey: String,
        ruleFile: File,
        block: suspend (DrpyEngine) -> T,
    ): T {
        semaphore.acquire()
        try {
            val engine = mutex.withLock {
                val cached = engines.remove(siteKey)
                val e = cached ?: run {
                    if (engines.size >= MAX_ENGINES) {
                        val oldest = engines.keys.firstOrNull()
                        if (oldest != null) {
                            runCatching { engines.remove(oldest)?.release() }
                            Log.i(TAG, "淘汰引擎: $oldest")
                        }
                    }
                    QuickJsDrpyEngine(SubSiteEngines.requireContext()).also {
                        it.loadRule(ruleFile.readText())
                    }
                }
                engines[siteKey] = e
                e
            }
            return block(engine)
        } finally {
            semaphore.release()
        }
    }
}

/** 订阅 PY 站的引擎适配器：PyApi 无状态，按 rulePath 直接调。 */
private class SubPyEngine(private val rulePath: String) : DrpyEngine {
    private val api: PyApi by lazy { PyApi(ChaquopyEngine) }

    companion object {
        private const val TAG = "SubPyEngine"
    }

    override fun loadRule(js: String) {}

    override suspend fun home(): List<Vod> =
        runCatching { api.home(rulePath) }
            .onFailure { LogBus.e(TAG, "PY home 失败 $rulePath: ${it.message}") }
            .getOrNull().orEmpty()

    override suspend fun categories(): List<CategoryType> =
        runCatching { api.categories(rulePath) }
            .onFailure { LogBus.e(TAG, "PY categories 失败 $rulePath: ${it.message}") }
            .getOrNull().orEmpty()

    override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> =
        runCatching { api.category(rulePath, tid, pg) }
            .onFailure { LogBus.e(TAG, "PY category 失败 $rulePath: ${it.message}") }
            .getOrNull().orEmpty()

    override suspend fun detail(id: String): VodDetail =
        runCatching { api.detail(rulePath, id) }
            .onFailure { LogBus.e(TAG, "PY detail 失败 $rulePath: ${it.message}") }
            .getOrNull() ?: VodDetail(vodId = id)

    override suspend fun search(wd: String): List<Vod> =
        runCatching { api.search(rulePath, wd) }
            .onFailure { LogBus.e(TAG, "PY search 失败 $rulePath: ${it.message}") }
            .getOrNull().orEmpty()

    override suspend fun play(flag: String, id: String): String =
        runCatching { api.play(rulePath, flag, id).first }.getOrNull().orEmpty()

    override fun release() {}
}
