package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Episode
import com.movieshell.data.model.ParseConfig
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.model.isCmsSite
import com.movieshell.data.model.isJavaSpider
import com.movieshell.data.model.isNovelOrComic
import com.movieshell.data.model.isPhpSite
import com.movieshell.data.model.isPySite
import com.movieshell.data.model.isType4Site
import com.movieshell.data.model.toSite
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DrpyEngine
import com.movieshell.data.source.TvBoxConfigParser
import com.movieshell.data.source.Type4Api
import com.movieshell.data.source.Type4PlayResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 聚合搜索的单站点结果分组：站点 + 该站点的搜索结果。
 */
data class SiteSearchResult(val site: Site, val vods: List<Vod>)

/**
 * 站源聚合层：UI / ViewModel 只跟这个类打交道。
 *
 * 职责：
 *  1. loadFromConfig() 解析 TVBox 订阅 JSON，沉淀可用站源列表；
 *  2. 按 site 把 home/category/detail/search 分发："php:" 走 PhpSourceManager（本地 PHP 规则，
 *     手机端无 PHP 解释器，明确不支持，返回空）、csp_ 开头的 Java spider 站（需 tvkj.jar，
 *     明确不支持，返回空）、type=0/1 走 CmsApi、type=4 走 Type4Api（api 是 PHP 脚本的
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

    /**
     * 从 TVBox 订阅 JSON 载入站源。
     * 过滤掉 api/key 为空的无效条目；返回本次载入的有效站源。
     */
    fun loadFromConfig(json: String): List<Site> {
        val config = TvBoxConfigParser.parseConfig(json)
        sites.clear()
        sites += config.sites
            .filter { it.api.isNotBlank() && it.key.isNotBlank() }
            .map { it.toSite() }
        return getEnabledSites()
    }

    /** 当前可用的全部站源。 */
    fun getEnabledSites(): List<Site> = sites.toList()

    /** 按 key 取站源，找不到返回 null。 */
    fun findSite(key: String): Site? = sites.find { it.key == key }

    /** 首页推荐/最新：PHP / csp_ Java spider / 小说漫画站明确不支持，返回空。 */
    suspend fun home(site: Site): List<Vod> =
        if (site.isPhpSite()) emptyList() // PHP 需 PHP 解释器，手机端暂不支持执行
        else if (site.isJavaSpider()) emptyList() // csp_ Java spider：需 tvkj.jar，无运行环境
        else if (site.isNovelOrComic()) emptyList() // 小说/漫画站：只做视频点播，跳过
        else if (site.isType4Site()) type4Api.home(site)
        else if (site.isCmsSite()) cmsApi.home(site)
        else if (site.isPySite()) runCatching { subOr(pyEngine) { pyEngineFor(site) }?.home() }.getOrNull().orEmpty()
        else runCatching { subOr(drpyEngine) { drpyEngineFor(site) }?.home() }.getOrNull().orEmpty()

    /** 分类：Pair(分类列表, 当前分类第 pg 页)。 */
    suspend fun category(site: Site, tid: String, pg: Int): Pair<List<CategoryType>, List<Vod>> =
        if (site.isPhpSite()) emptyList<CategoryType>() to emptyList() // PHP 暂不支持执行
        else if (site.isJavaSpider()) emptyList<CategoryType>() to emptyList()
        else if (site.isType4Site()) type4Api.category(site, tid, pg)
        else if (site.isCmsSite()) cmsApi.category(site, tid, pg)
        else if (site.isPySite()) runCatching { subOr(pyEngine) { pyEngineFor(site) }?.categories() }.getOrNull().orEmpty() to
            runCatching { subOr(pyEngine) { pyEngineFor(site) }?.category(tid, pg) }.getOrNull().orEmpty()
        else emptyList<CategoryType>() to
            runCatching { subOr(drpyEngine) { drpyEngineFor(site) }?.category(tid, pg) }.getOrNull().orEmpty()

    /** 详情。 */
    suspend fun detail(site: Site, vodId: String): VodDetail =
        if (site.isPhpSite()) VodDetail(vodId = vodId) // PHP 暂不支持执行
        else if (site.isJavaSpider()) VodDetail(vodId = vodId)
        else if (site.isType4Site()) type4Api.detail(site, vodId)
        else if (site.isCmsSite()) cmsApi.detail(site, vodId)
        else if (site.isPySite()) runCatching { subOr(pyEngine) { pyEngineFor(site) }?.detail(vodId) }.getOrNull()
            ?: VodDetail(vodId = vodId)
        else runCatching { subOr(drpyEngine) { drpyEngineFor(site) }?.detail(vodId) }.getOrNull()
            ?: VodDetail(vodId = vodId)

    /** 搜索；站源声明 searchable=0 时直接返回空，不发请求。非 CMS 站走 drpy/PY 引擎。 */
    suspend fun search(site: Site, wd: String): List<Vod> {
        if (!site.searchable || wd.isBlank()) return emptyList()
        return if (site.isPhpSite()) emptyList() // PHP 暂不支持执行
        else if (site.isJavaSpider()) emptyList()
        else if (site.isNovelOrComic()) emptyList() // 小说/漫画站：只做视频点播，跳过
        else if (site.isType4Site()) type4Api.search(site, wd)
        else if (site.isCmsSite()) cmsApi.search(site, wd)
        else if (site.isPySite()) runCatching { subOr(pyEngine) { pyEngineFor(site) }?.search(wd) }.getOrNull().orEmpty()
        else runCatching { subOr(drpyEngine) { drpyEngineFor(site) }?.search(wd) }.getOrNull().orEmpty()
    }

    /**
     * 聚合搜索：对所有已启用站源并发搜索。
     *
     * - 复用单源 search()（CMS / drpy / PY 各走原实现，不重写）；
     * - 每个站源独立协程 + supervisorScope：单个源失败/超时只跳过该源，不影响其他源；
     * - 每个源超时 timeoutMs（默认 15 秒），超时/失败静默返回空；
     * - 网页源是 URL 直达形态、无搜索接口，天然不在 sites 列表中（已跳过）；
     * - 返回按站源原顺序排列的分组，只含结果非空的站源。
     */
    suspend fun searchAll(wd: String, timeoutMs: Long = 15_000): List<SiteSearchResult> {
        if (wd.isBlank()) return emptyList()
        val targets = getEnabledSites()
        return supervisorScope {
            targets.map { site ->
                async {
                    val vods = runCatching {
                        withTimeoutOrNull(timeoutMs) { search(site, wd) }
                    }.getOrNull().orEmpty()
                    SiteSearchResult(site, vods)
                }
            }.awaitAll()
        }.filter { it.vods.isNotEmpty() }
    }

    /**
     * 播放分集：将 vodPlayFrom 的来源名与 vodPlayUrl 的分组按 $$$ 顺序对齐。
     * playFrom 为空时来源自动编号为 线路1/2…。
     */
    fun parseEpisodes(detail: VodDetail): List<Episode> =
        cmsApi.parsePlayUrl(detail.vodPlayUrl, detail.vodPlayFrom)

    /** drpy 播放：v0.1 引擎未实现，返回空串。 */
    suspend fun drpyPlay(flag: String, id: String): String =
        runCatching { drpyEngine?.play(flag, id) }.getOrNull().orEmpty()

    /** PY 播放：playerContent 返回的直链 url。 */
    suspend fun pyPlay(flag: String, id: String): String =
        runCatching { pyEngine?.play(flag, id) }.getOrNull().orEmpty()

    /** PHP 播放：手机端无 PHP 解释器，暂不支持，返回空串。 */
    suspend fun phpPlay(flag: String, id: String): String = ""

    /**
     * type=4 播放：GET {api}?ac=play&flag={flag}&id={id}，返回 (直链, parse 标记, header)。
     * parse=1 的站走 [type4PlayWithParses] 分流进订阅 parses 解析链路；
     * 本函数是"无 parses 上下文"时的直链版本（parse=1 时按 v0.6 老行为原样返回）。
     * header 由调用方（DetailScreen）经 SniffPlayHeaders.put 透传给播放器。
     * 任何失败返回空结果，不抛异常。
     */
    suspend fun type4Play(site: Site, flag: String, id: String): Type4PlayResult =
        runCatching { type4Api.play(site, flag, id) }.getOrNull() ?: Type4PlayResult()

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
