package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Episode
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.ParseConfig
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.model.isCmsSite
import com.movieshell.data.model.isDrpySite
import com.movieshell.data.model.isJavaSpider
import com.movieshell.data.model.isNovelOrComic
import com.movieshell.data.model.isPhpSite
import com.movieshell.data.model.isPySite
import com.movieshell.data.model.isType4Site
import com.movieshell.data.model.isUnsupportedSite
import com.movieshell.data.model.toSite
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DrpyEngine
import com.movieshell.data.source.ParseApi
import com.movieshell.data.source.TvBoxConfigParser
import com.movieshell.data.source.Type4Api
import com.movieshell.data.source.Type4PlayResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * 聚合搜索的单站点结果分组：站点 + 该站点的搜索结果。
 */
data class SiteSearchResult(val site: Site, val vods: List<Vod>)

/**
 * 站源聚合层：UI / ViewModel 只跟这个类打交道。
 *
 * 职责：
 *  1. loadFromConfig() 解析 TVBox 订阅 JSON，沉淀可用站源列表；
 *  2. 按 site 把 home/category/detail/search 分发："php:" 走 PhpCliEngine（零端口 CLI，
 *     有请求才跑 php，跑完退出，解析复用 Type4Api）、csp_ 开头的 Java spider 站走 SpiderEngine
 *     （v0.8.0：按订阅下载 jar，DexClassLoader 动态加载执行）、type=0/1 走 CmsApi、type=4 走 Type4Api（api 是 PHP 脚本的
 *     HTTP 地址，壳子只发 HTTP 解析 JSON）、PY 站（type=3 且 api 以 .py 结尾）走 PY 引擎、
 *     drpy 站（api 以 .js 结尾）走 drpy 引擎；小说/漫画站（扩展字段"类型"）跳过 home/search；
 *     订阅展开的子站点（key 前缀 "sub:"）走 SubSiteEngines 的按站引擎；
 *  3. parseEpisodes() 把详情里的 vod_play_from / vod_play_url 对齐成 Episode 列表。
 */
class SiteRepository(
    private val cmsApi: CmsApi = CmsApi(),
    private val drpyEngine: DrpyEngine? = null,
    private val pyEngine: DrpyEngine? = null,
    private val type4Api: Type4Api = Type4Api()
) {

    private val sites = mutableListOf<Site>()

    companion object {
        private const val TAG = "SiteRepo"

        /** v0.101.0：聚合搜索并发上限，20 个源齐发会被反爬封 IP。 */
        private const val SEARCH_MAX_CONCURRENCY = 4

        /**
         * v0.187.0 H04：按站 key 记最近一次失败原因（内存态，不落盘）。
         * 放伴生对象：调用方常新建 SiteRepository 实例，实例字段会在实例间丢原因，
         * 伴生 map 才能让“这次调用空结果有没有原因”在不同实例间也可查。
         * 语义是最近一次：每次入口调用先清、本次失败再记；空结果且查无原因 =
         * 源确实没数据，空结果且有原因 = 执行失败（UI 空态展示延期，见任务说明）。
         */
        private val lastFailureBySite = ConcurrentHashMap<String, String>()

        /**
         * v0.188.0 M28：最近一次 searchAll 里失败的源（超时 + 普通失败，
         * 去重按源名）。供结果页脚汇总“X 个源失败”用，口径与
         * SearchSlowSourceFilter 的 skippedLabel 同款（VM 侧拼进页脚文案）。
         * 注意：无结果但无失败原因的源是“确实没数据”，不计入失败。
         */
        @Volatile
        var lastSearchFailedCount: Int = 0
            private set
        @Volatile
        var lastSearchFailedNames: List<String> = emptyList()
            private set

        /** 最近一次聚合搜索的失败汇总文案，null=本次无源失败。 */
        fun failedSearchLabel(): String? =
            if (lastSearchFailedCount > 0) "$lastSearchFailedCount 个源失败" else null

        /** searchAll 内部写失败汇总的唯一入口（private set 只能由伴生内写）。 */
        internal fun recordSearchFailed(names: Set<String>) {
            lastSearchFailedNames = names.sorted()
            lastSearchFailedCount = names.size
        }
    }

    /** 最近一次失败原因（按站）：null = 最近一次调用无失败（或未跑过）。 */
    fun lastFailureReason(site: Site): String? = lastFailureBySite[site.key]

    /** 最近一次失败原因（按站 key）。 */
    fun lastFailureReason(siteKey: String): String? = lastFailureBySite[siteKey]

    /** v0.101.0：聚合搜索并发限流（与小说/音乐模块各自独立）。 */
    private val searchSemaphore = Semaphore(SEARCH_MAX_CONCURRENCY)

    /**
     * v0.7.2：带日志的 runCatching。失败记 LogBus.E，返回 null。
     * v0.187.0 H04：失败同时按站 key 记进 [lastFailureBySite]，不再只记日志、
     * 调用方仍对空结果一无所知；异常没带 message 时用类名兜底，不记空原因。
     */
    private inline fun <T> logged(site: Site, op: String, block: () -> T): T? =
        runCatching(block)
            .onFailure {
                val reason = it.message?.takeIf { m -> m.isNotBlank() }
                    ?: it::class.java.simpleName
                lastFailureBySite[site.key] = reason
                LogBus.e(TAG, "$op 失败：$reason")
            }
            .getOrNull()

    /**
     * v0.187.0 H04：引擎“没抛异常但交了空结果”时的原因桥接。
     * 引擎内部失败（如 V8 超时、Type4 请求失败、PHP 退出码）只写自家 lastError、
     * 不抛异常，logged 抓不到；空结果时把对应引擎的 lastError 记进按站 map，
     * 已有 logged 记的原因不覆盖（putIfAbsent）。非空结果不记。
     */
    private fun recordEngineFailureIfEmpty(site: Site, isEmpty: Boolean) {
        if (!isEmpty) return
        // 分支必须与 home/category/detail/search 的分流严格对齐：CMS/蜘蛛站没有
        // lastError 载体、小说站是主动跳过，都不能误读默认 drpy 引擎的旧原因。
        val reason = when {
            site.isPhpSite() -> PhpCliEngine.lastError
            site.isType4Site() -> type4Api.lastError
            site.isPySite() -> (subOr(pyEngine) { pyEngineFor(site) } ?: pyEngine)?.lastError
            site.isCmsSite() || site.isJavaSpider() || site.isNovelOrComic() || site.isUnsupportedSite() -> null
            else -> (subOr(drpyEngine) { drpyEngineFor(site) } ?: drpyEngine)?.lastError
        } ?: return
        lastFailureBySite.putIfAbsent(site.key, reason)
    }

    /**
     * 从 TVBox 订阅 JSON 载入站源。
     * 过滤掉 api/key 为空的无效条目；返回本次载入的有效站源。
     *
     * v0.171.0：先载入到临时结构、校验通过才替换现有列表——非法订阅
     * （顶层无 sites 数组）直接保留旧站源并返回旧列表，不再先 clear 再载入
     * 导致旧站源被清空。合法订阅里的重复站源按 key 与归一化 URL 去重
     * （key 相同、或 URL 仅差尾斜杠/域名大小写/默认端口时算同一站源，首条胜出）。
     */
    @Synchronized
    fun loadFromConfig(json: String): List<Site> {
        if (!TvBoxConfigParser.hasSitesArray(json)) {
            LogBus.w(TAG, "非法订阅：顶层无 sites 数组，已保留现有 ${sites.size} 个站源")
            return getEnabledSites()
        }
        val config = TvBoxConfigParser.parseConfig(json)
        val fresh = config.sites
            .filter { it.api.isNotBlank() && it.key.isNotBlank() }
            .map { it.toSite() }
            .distinctBy { normalizeSiteKey(it) }
        if (fresh.isEmpty()) {
            // sites 数组合法但过滤后一条有效站源都没有：同样不清空旧列表，
            // 否则一次残缺订阅就能把现有站源全部抹掉
            LogBus.w(TAG, "订阅 sites 数组无有效条目，已保留现有 ${sites.size} 个站源")
            return getEnabledSites()
        }
        sites.clear()
        sites += fresh
        return getEnabledSites()
    }

    /** 去重键：key（去空白）+ 归一化 URL；同一站源的不同写法落到同一键。 */
    private fun normalizeSiteKey(site: Site): String {
        val key = site.key.trim()
        val url = site.api.trim().removeSuffix("/").let { raw ->
            runCatching {
                val schemeEnd = raw.indexOf("://")
                if (schemeEnd < 0) return@let raw
                val scheme = raw.substring(0, schemeEnd).lowercase()
                val rest = raw.substring(schemeEnd + 3)
                val hostEnd = rest.indexOf('/')
                val hostPort = if (hostEnd < 0) rest else rest.substring(0, hostEnd)
                val path = if (hostEnd < 0) "" else rest.substring(hostEnd)
                val host = hostPort.substringBefore(':').lowercase()
                val port = hostPort.substringAfter(':', "")
                val defaultPort = if (scheme == "https") "443" else "80"
                val hostPart = if (port.isBlank() || port == defaultPort) host else "$host:$port"
                "$scheme://$hostPart$path"
            }.getOrDefault(raw)
        }
        return "$key|$url"
    }

    /** 当前可用的全部站源。 */
    @Synchronized
    fun getEnabledSites(): List<Site> = sites.toList()

    /** 按 key 取站源，找不到返回 null。 */
    @Synchronized
    fun findSite(key: String): Site? = sites.find { it.key == key }

    /** 首页推荐/最新：PHP 走 CLI 引擎；小说漫画站明确不支持，返回空；csp_ 站走 SpiderEngine。 */
    suspend fun home(site: Site): List<Vod> {
        lastFailureBySite.remove(site.key)
        LogBus.i(TAG, "home site=${site.name}")
        val r = if (site.isPhpSite()) logged(site, "php.home ${site.name}") { PhpCliEngine.home(scriptName = PhpCliEngine.scriptNameForSite(site.key, site.api)) }.orEmpty()
        else if (site.isJavaSpider()) logged(site, "spider.home ${site.name}") { SpiderEngine.home(site) }.orEmpty()
        else if (site.isNovelOrComic()) emptyList() // 小说/漫画站：只做视频点播，跳过
        else if (site.isType4Site()) logged(site, "type4.home ${site.name}") { type4Api.home(site) }.orEmpty()
        else if (site.isCmsSite()) logged(site, "cms.home ${site.name}") { cmsApi.home(site) }.orEmpty()
        else if (site.isPySite()) logged(site, "py.home ${site.name}") { subOr(pyEngine) { pyEngineFor(site) }?.home() }.orEmpty()
        else if (site.isUnsupportedSite()) {
            // v0.188.0 M15：未知类型/地址不再静默按 drpy 跑，明确走不支持空态。
            LogBus.w(TAG, "不支持的站源，已跳过：${site.name} type=${site.type} api=${site.api.take(80)}")
            emptyList()
        }
        else logged(site, "drpy.home ${site.name}") { subOr(drpyEngine) { drpyEngineFor(site) }?.home() }.orEmpty()
        recordEngineFailureIfEmpty(site, r.isEmpty())
        LogBus.i(TAG, "home site=${site.name} 结果 ${r.size} 条")
        return r
    }

    /**
     * 分类：Pair(分类列表, 当前分类第 pg 页)。
     * @param filter drpy 站筛选键→选中值（对应规则 filter_url 里 {{fl.键名}} 的键名）；
     * UI 筛选器后续接入后传入，当前调用方传空 map 即按规则默认值渲染。
     */
    suspend fun category(
        site: Site,
        tid: String,
        pg: Int,
        filter: Map<String, String> = emptyMap()
    ): Pair<List<CategoryType>, List<Vod>> {
        lastFailureBySite.remove(site.key)
        LogBus.i(TAG, "category site=${site.name} tid=$tid pg=$pg")
        val r = if (site.isPhpSite()) run {
            val script = PhpCliEngine.scriptNameForSite(site.key, site.api)
            val types = logged(site, "php.categories ${site.name}") { PhpCliEngine.categories(scriptName = script) }.orEmpty()
            if (tid.isBlank()) types to emptyList()
            else types to (logged(site, "php.category ${site.name}") { PhpCliEngine.category(tid = tid, pg = pg, scriptName = script) }.orEmpty())
        }
        else if (site.isJavaSpider()) logged(site, "spider.category ${site.name}") { SpiderEngine.category(site, tid, pg) }
            .orEmptyCategory()
        // v0.188.0 M15：category 补小说站跳过分支，与 home/search 口径对齐
        // （此前小说站进分类仍走引擎链路）。
        else if (site.isNovelOrComic()) emptyList<CategoryType>() to emptyList()
        else if (site.isType4Site()) logged(site, "type4.category ${site.name}") { type4Api.category(site, tid, pg) }
            .orEmptyCategory()
        else if (site.isCmsSite()) logged(site, "cms.category ${site.name}") { cmsApi.category(site, tid, pg) }
            .orEmptyCategory()
        else if (site.isPySite()) logged(site, "py.categories ${site.name}") { subOr(pyEngine) { pyEngineFor(site) }?.categories() }.orEmpty() to
            logged(site, "py.category ${site.name}") { subOr(pyEngine) { pyEngineFor(site) }?.category(tid, pg, filter) }.orEmpty()
        else if (site.isUnsupportedSite()) {
            LogBus.w(TAG, "不支持的站源，已跳过：${site.name} type=${site.type} api=${site.api.take(80)}")
            emptyList<CategoryType>() to emptyList()
        }
        else emptyList<CategoryType>() to
            logged(site, "drpy.category ${site.name}") { subOr(drpyEngine) { drpyEngineFor(site) }?.category(tid, pg, filter) }.orEmpty()
        recordEngineFailureIfEmpty(site, r.second.isEmpty())
        LogBus.i(TAG, "category site=${site.name} 结果 ${r.second.size} 条")
        return r
    }

    private fun Pair<List<CategoryType>, List<Vod>>?.orEmptyCategory(): Pair<List<CategoryType>, List<Vod>> =
        this ?: (emptyList<CategoryType>() to emptyList())

    /**
     * drpy 站某分类的筛选定义（规则 filter 分组），给 UI 筛选器用。
     * 非 drpy 站 / 规则没配 filter 时返回空列表。
     * v0.188.0 M15：filters 口径与 home/search 对齐——小说/漫画站明确跳过
     * （此前小说站 api 若以 .js 结尾会走到 drpy 筛选，与 home/search 的
     * 跳过口径不一致）。UI 侧说明：筛选入口只在 filters 非空时展示，
     * 非 drpy 站本来就没有筛选定义，返回空即等于隐藏筛选入口，不另起一套 UI。
     */
    suspend fun filters(site: Site, tid: String): List<FilterGroup> {
        if (site.isNovelOrComic()) return emptyList()
        if (!site.isDrpySite()) return emptyList()
        lastFailureBySite.remove(site.key)
        val result = runCatching { subOr(drpyEngine) { drpyEngineFor(site) }?.filters(tid) }
            .onFailure {
                val reason = it.message?.takeIf { m -> m.isNotBlank() } ?: it::class.java.simpleName
                lastFailureBySite[site.key] = reason
                LogBus.e(TAG, "drpy.filters ${site.name} 失败：$reason")
            }
            .getOrNull().orEmpty()
        recordEngineFailureIfEmpty(site, result.isEmpty())
        return result
    }

    /** 详情。 */
    suspend fun detail(site: Site, vodId: String): VodDetail {
        lastFailureBySite.remove(site.key)
        LogBus.i(TAG, "detail site=${site.name} id=$vodId")
        val r = if (site.isPhpSite()) logged(site, "php.detail ${site.name}") { PhpCliEngine.detail(vodId = vodId, scriptName = PhpCliEngine.scriptNameForSite(site.key, site.api)) }
            ?: VodDetail(vodId = vodId)
        else if (site.isJavaSpider()) logged(site, "spider.detail ${site.name}") { SpiderEngine.detail(site, vodId) }
            ?: VodDetail(vodId = vodId)
        // v0.188.0 M15：detail 补小说站跳过分支，与 home/search 口径对齐。
        else if (site.isNovelOrComic()) VodDetail(vodId = vodId)
        else if (site.isType4Site()) logged(site, "type4.detail ${site.name}") { type4Api.detail(site, vodId) }
            ?: VodDetail(vodId = vodId)
        else if (site.isCmsSite()) logged(site, "cms.detail ${site.name}") { cmsApi.detail(site, vodId) }
            ?: VodDetail(vodId = vodId)
        else if (site.isPySite()) logged(site, "py.detail ${site.name}") { subOr(pyEngine) { pyEngineFor(site) }?.detail(vodId) }
            ?: VodDetail(vodId = vodId)
        else if (site.isUnsupportedSite()) {
            LogBus.w(TAG, "不支持的站源，已跳过：${site.name} type=${site.type} api=${site.api.take(80)}")
            VodDetail(vodId = vodId)
        }
        else logged(site, "drpy.detail ${site.name}") { subOr(drpyEngine) { drpyEngineFor(site) }?.detail(vodId) }
            ?: VodDetail(vodId = vodId)
        recordEngineFailureIfEmpty(site, r.vodName.isBlank() && r.vodPlayUrl.isBlank())
        LogBus.i(TAG, "detail site=${site.name} 播放源 ${if (r.vodPlayUrl.isBlank()) "空" else "有数据"}")
        // v0.14.5：详情播放地址明细（截断）
        if (r.vodPlayUrl.isNotBlank()) {
            LogBus.i(TAG, "detail playUrl: ${r.vodPlayUrl.take(150)}${if (r.vodPlayUrl.length > 150) "..." else ""}")
        }
        if (r.vodPlayFrom.isNotBlank()) {
            LogBus.i(TAG, "detail playFrom: ${r.vodPlayFrom.take(100)}")
        }
        return r
    }

    /** 搜索；站源声明 searchable=0 时直接返回空，不发请求。非 CMS 站走 drpy/PY 引擎。 */
    suspend fun search(site: Site, wd: String): List<Vod> {
        lastFailureBySite.remove(site.key)
        if (!site.searchable || wd.isBlank()) return emptyList()
        LogBus.i(TAG, "search site=${site.name} wd=$wd")
        val r = if (site.isPhpSite()) logged(site, "php.search ${site.name}") { PhpCliEngine.search(wd = wd, scriptName = PhpCliEngine.scriptNameForSite(site.key, site.api)) }.orEmpty()
        else if (site.isJavaSpider()) logged(site, "spider.search ${site.name}") { SpiderEngine.search(site, wd) }.orEmpty()
        else if (site.isNovelOrComic()) emptyList() // 小说/漫画站：只做视频点播，跳过
        // v0.189.0：聚合搜索的本地 drpy 合成站（key=drpy:active，type=4）必须先于
        // type4 分发，否则会被当成 PHP 远端站走 Type4Api、api="drpy" 建不出 URL、
        // 聚合搜索里的 drpy 条目一直空着；这里按 key 分流到当前激活 drpy 引擎。
        // v0.190.0：逐条搜本地规则的 local:drpy: 站同理（type=4、api 是文件路径），
        // 也先于 type4 分发，按站取池化引擎；否则每条都会错走到远端 PHP。
        else if (site.key == "drpy:active" || site.key.startsWith("local:drpy:")) logged(site, "drpy.search ${site.name}") { subOr(drpyEngine) { drpyEngineFor(site) }?.search(wd) }.orEmpty()
        else if (site.isType4Site()) logged(site, "type4.search ${site.name}") { type4Api.search(site, wd) }.orEmpty()
        else if (site.isCmsSite()) logged(site, "cms.search ${site.name}") { cmsApi.search(site, wd) }.orEmpty()
        else if (site.isPySite()) logged(site, "py.search ${site.name}") { subOr(pyEngine) { pyEngineFor(site) }?.search(wd) }.orEmpty()
        else if (site.isUnsupportedSite()) {
            LogBus.w(TAG, "不支持的站源，已跳过：${site.name} type=${site.type} api=${site.api.take(80)}")
            emptyList()
        }
        else logged(site, "drpy.search ${site.name}") { subOr(drpyEngine) { drpyEngineFor(site) }?.search(wd) }.orEmpty()
        recordEngineFailureIfEmpty(site, r.isEmpty())
        LogBus.i(TAG, "search site=${site.name} 结果 ${r.size} 条")
        return r
    }

    /**
     * v0.191.0：单源聚合搜索（searchAll / searchAllFlow 共用，禁各写一份）。
     * 语义与旧 searchAll 内联版逐字一致：限流 + 单源 15 秒超时 + 超时才计慢源 +
     * 调用方取消透传 + 内部失败（logged 吞成空）按失败原因计入失败源。
     */
    private suspend fun searchOneSite(
        site: Site,
        wd: String,
        timeoutMs: Long,
        failedNames: MutableSet<String>,
    ): SiteSearchResult {
        val vods = try {
            searchSemaphore.withPermit {
                withTimeout(timeoutMs) { search(site, wd) }
            }.also { SearchSlowSourceFilter.onSuccess(site.key) }
        } catch (e: TimeoutCancellationException) {
            // v0.162.0：只有超时才计入慢源，普通失败不计
            SearchSlowSourceFilter.onTimeout(site.key)
            LogBus.w(TAG, "聚合搜索源超时：${site.name}")
            failedNames += site.name
            emptyList()
        } catch (e: CancellationException) {
            // 调用方取消（切页/新搜索）不是源失败：透传，不记失败、不吞。
            throw e
        } catch (e: Exception) {
            // v0.188.0 M28：普通失败此前直接 emptyList() 且无日志，
            // 失败源在聚合搜索里静默消失。现记带源名的 warning 并计入失败。
            LogBus.w(TAG, "聚合搜索源失败：${site.name}：${e.message ?: e::class.java.simpleName}")
            failedNames += site.name
            emptyList()
        }
        // search() 内部失败不抛异常（logged 吞成空结果）：空结果且
        // 按站留有失败原因的，同样是失败源，记 warning 并计入失败；
        // 空结果且无原因的，是源确实没数据，不计。
        if (vods.isEmpty()) {
            lastFailureReason(site)?.let { reason ->
                LogBus.w(TAG, "聚合搜索源失败：${site.name}：$reason")
                failedNames += site.name
            }
        }
        return SiteSearchResult(site, vods)
    }

    /** 聚合搜索目标站：启用站去黑名单，跳过数记 filter 供结果页提示（两路共用）。 */
    private fun searchTargets(): List<Site> {
        val all = getEnabledSites()
        val targets = all.filter { !SearchSlowSourceFilter.shouldSkip(it.key) }
        SearchSlowSourceFilter.setLastSkipped(all.size - targets.size)
        return targets
    }

    /**
     * 聚合搜索：对所有已启用站源并发搜索。
     *
     * - 复用单源 search()（CMS / drpy / PY 各走原实现，不重写）；
     * - 每个站源独立协程 + supervisorScope：单个源失败/超时只跳过该源，不影响其他源；
     * - 每个源超时 timeoutMs（默认 15 秒），超时/失败静默返回空；
     * - v0.101.0：Semaphore(4) 限流，个人家用 20 个源齐发易被反爬封 IP；
     *   结果先到先等，awaitAll 返回后按原顺序展示，流式行为不变；
     * - 网页源是 URL 直达形态、无搜索接口，天然不在 sites 列表中（已跳过）；
     * - 返回按站源原顺序排列的分组，只含结果非空的站源。
     * - v0.191.0：搜索页不再用本函数（见 searchAllFlow），演员作品页仍用本函数
     *   （跨源去重需要一次性全量，按原顺序取首个，不跟流式抢顺序）。
     */
    suspend fun searchAll(wd: String, timeoutMs: Long = 15_000): List<SiteSearchResult> {
        if (wd.isBlank()) return emptyList()
        val targets = searchTargets()
        // v0.188.0 M28：本轮失败源集合（并发写，用并发 set 去重）。
        val failedNames = ConcurrentHashMap.newKeySet<String>()
        val results = supervisorScope {
            targets.map { site ->
                async { searchOneSite(site, wd, timeoutMs, failedNames) }
            }.awaitAll()
        }
        recordSearchFailed(failedNames)
        if (failedNames.isNotEmpty()) {
            LogBus.w(TAG, "聚合搜索失败源 ${failedNames.size} 个：${failedNames.sorted().joinToString()}")
        }
        return results.filter { it.vods.isNotEmpty() }
    }

    /**
     * v0.192.0：左栏用的本次搜索目标站（启用站去黑名单后）。
     * ViewModel 在 loadFromConfig 后立刻调它，先把左栏站名列出来；
     * searchAllFlow 里会再算一次同一集合（两次调用之间没有搜索在跑，
     * 黑名单是会话态、不会自己变，所以两次结果一致，不会错位）。
     */
    fun searchTargetSites(): List<Site> = searchTargets()

    /**
     * v0.191.0：流式聚合搜索，搜索页专用。
     * 根因：旧 searchAll 用 awaitAll 等全部源跑完才一次性返回，SearchViewModel
     * 拿到结果前 groupedResults 一直是 null，搜索页只能干放骨架屏——日志里
     * 早已回来的几十个源（本轮“手”：275听书 50 条、8j 40 条等）用户一个都看不到，
     * 150+ 个源 × 4 并发 × 单源 15 秒超时，全跑完要几分钟。
     * 改：每源一跑完就 send 一个分组，调用方先到先显示；失败汇总
     * 仍在全部跑完后才记（joinAll 后），搜中途不给“X 个源失败”半截数。
     * 分组按先到先出，不再按站源原顺序——这是流式显示的代价，页脚会写明。
     * 取消语义与 searchAll 同款：调用方取消透传，不记失败。
     * v0.192.0：空结果也发（调用方只把非空的放进展示列表，空的只计
     * “已搜数”与左栏状态），否则左栏里没结果的源永远停在“搜索中…”。
     */
    fun searchAllFlow(wd: String, timeoutMs: Long = 15_000): Flow<SiteSearchResult> = channelFlow {
        if (wd.isBlank()) return@channelFlow
        val targets = searchTargets()
        val failedNames = ConcurrentHashMap.newKeySet<String>()
        val jobs = targets.map { site ->
            launch {
                val result = searchOneSite(site, wd, timeoutMs, failedNames)
                send(result)
            }
        }
        jobs.joinAll()
        recordSearchFailed(failedNames)
        if (failedNames.isNotEmpty()) {
            LogBus.w(TAG, "聚合搜索失败源 ${failedNames.size} 个：${failedNames.sorted().joinToString()}")
        }
    }

    /**
     * 播放分集：将 vodPlayFrom 的来源名与 vodPlayUrl 的分组按 $$$ 顺序对齐。
     * playFrom 为空时来源自动编号为 线路1/2…。
     */
    fun parseEpisodes(detail: VodDetail): List<Episode> =
        cmsApi.parsePlayUrl(detail.vodPlayUrl, detail.vodPlayFrom)


    /**
     * type=4 播放：GET {api}?ac=play&flag={flag}&id={id}，返回 (直链, parse 标记, header)。
     * parse=1 的站走 [type4PlayWithParses] 分流进订阅 parses 解析链路；
     * 本函数是"无 parses 上下文"时的直链版本（parse=1 时按 v0.6 老行为原样返回）。
     * header 由调用方（DetailScreen）经 SniffPlayHeaders.put 透传给播放器。
     * 任何失败返回空结果，不抛异常。
     */
    suspend fun type4Play(site: Site, flag: String, id: String): Type4PlayResult {
        lastFailureBySite.remove(site.key)
        val result = runCatching { type4Api.play(site, flag, id) }
            .onFailure {
                val reason = it.message?.takeIf { m -> m.isNotBlank() } ?: it::class.java.simpleName
                lastFailureBySite[site.key] = reason
                LogBus.e(TAG, "type4.play ${site.name} 失败：$reason")
            }
            .getOrNull() ?: Type4PlayResult()
        // 本函数恒走 type4Api，不按站类型分流，直接读它（不用通用桥接）。
        if (result.url.isBlank()) {
            type4Api.lastError?.let { lastFailureBySite.putIfAbsent(site.key, it) }
        }
        return result
    }

    /**
     * type=4 的 parse=1 分流（v0.7）：Type4Api 返回 parse=1 时，用该订阅的 parses
     * 数组走 ParseApi 按顺序自动解析拿直链；全部失败返回空结果
     * （调用方 UI 层 Toast "解析失败"）。
     * parse=0 / 无 parses 时行为与 [type4Play] 一致（直链直接返回）。不抛异常。
     */
    suspend fun type4PlayWithParses(
        site: Site,
        flag: String,
        id: String,
        parses: List<ParseConfig>,
        context: Context,
    ): Type4PlayResult = runCatching {
        val r = type4Api.play(site, flag, id)
        if (r.parse != 1 || r.url.isBlank() || parses.isEmpty()) return@runCatching r
        val direct = ParseApi().parse(parses, r.url, context)
        if (direct.isBlank()) r.copy(url = "") else r.copy(url = direct)
    }.getOrDefault(Type4PlayResult())

    /**
     * spider 播放：playerContent(flag, id) 拿播放 JSON {parse,url,header}。
     * 任何失败返回空结果，不抛异常。
     */
    suspend fun spiderPlay(site: Site, flag: String, id: String): SpiderPlayResult =
        runCatching { SpiderEngine.play(site, flag, id) }.getOrDefault(SpiderPlayResult())

    /**
     * spider 的 parse=1 分流（v0.8.0）：playerContent 返回 parse=1 时，用该订阅的
     * parses 数组走 ParseApi 按顺序自动解析拿直链；全部失败返回空结果
     * （调用方 UI 层 Toast "解析失败"）。parse=0 时行为与 [spiderPlay] 一致。
     * header 由调用方（DetailScreen）经 SniffPlayHeaders.put 透传给播放器。不抛异常。
     */
    suspend fun spiderPlayWithParses(
        site: Site,
        flag: String,
        id: String,
        parses: List<ParseConfig>,
        context: Context,
    ): SpiderPlayResult = runCatching {
        val r = SpiderEngine.play(site, flag, id)
        if (r.parse != 1 || r.url.isBlank() || parses.isEmpty()) return@runCatching r
        val direct = ParseApi().parse(parses, r.url, context)
        if (direct.isBlank()) r.copy(url = "") else r.copy(url = direct)
    }.getOrDefault(SpiderPlayResult())

    /**
     * 订阅子站点优先用其专属引擎（SubSiteEngines 按 key 前缀 "sub:" 识别），
     * 非订阅站 / 识别失败时回退到构造时传入的默认引擎。
     */
    private fun subOr(
        default: DrpyEngine?,
        sub: SubSiteEngines.() -> DrpyEngine?,
    ): DrpyEngine? = try {
        SubSiteEngines.sub() ?: default
    } catch (e: Exception) {
        default
    }
}
