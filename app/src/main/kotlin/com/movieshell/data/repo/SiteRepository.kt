package com.movieshell.data.repo

import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Episode
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.model.toSite
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DrpyEngine
import com.movieshell.data.source.TvBoxConfigParser

/**
 * 站源聚合层：UI / ViewModel 只跟这个类打交道。
 *
 * 职责：
 *  1. loadFromConfig() 解析 TVBox 订阅 JSON，沉淀可用站源列表；
 *  2. 按 site 把 home/category/detail/search 分发：key 前缀 "ai:" 走 AiSourceManager、
 *     "php:" 走 PhpSourceManager（明确不支持，返回空）、type=0/1 走 CmsApi、
 *     PY 站（type=3 或 api 以 .py 结尾）走 PY 引擎，其余走 drpy 引擎；
 *  3. parseEpisodes() 把详情里的 vod_play_from / vod_play_url 对齐成 Episode 列表。
 */
class SiteRepository(
    private val cmsApi: CmsApi = CmsApi(),
    private val drpyEngine: DrpyEngine? = null,
    private val pyEngine: DrpyEngine? = null
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

    /** 首页推荐/最新：AI 合成站走 AiSourceManager；PHP 明确不支持，返回空。 */
    suspend fun home(site: Site): List<Vod> =
        if (site.isAi()) runCatching { AiSourceManager.home() }.getOrNull().orEmpty()
        else if (site.isPhp()) emptyList() // PHP 需 PHP 解释器，手机端暂不支持执行
        else if (site.type.isCms()) cmsApi.home(site)
        else if (site.isPy()) runCatching { pyEngine?.home() }.getOrNull().orEmpty()
        else runCatching { drpyEngine?.home() }.getOrNull().orEmpty()

    /** 分类：Pair(分类列表, 当前分类第 pg 页)。 */
    suspend fun category(site: Site, tid: String, pg: Int): Pair<List<CategoryType>, List<Vod>> =
        if (site.isAi()) runCatching { AiSourceManager.categories() }.getOrNull().orEmpty() to
            runCatching { AiSourceManager.category(tid, pg) }.getOrNull().orEmpty()
        else if (site.isPhp()) emptyList<CategoryType>() to emptyList() // PHP 暂不支持执行
        else if (site.type.isCms()) cmsApi.category(site, tid, pg)
        else if (site.isPy()) runCatching { pyEngine?.categories() }.getOrNull().orEmpty() to
            runCatching { pyEngine?.category(tid, pg) }.getOrNull().orEmpty()
        else emptyList<CategoryType>() to
            runCatching { drpyEngine?.category(tid, pg) }.getOrNull().orEmpty()

    /** 详情。 */
    suspend fun detail(site: Site, vodId: String): VodDetail =
        if (site.isAi()) runCatching { AiSourceManager.detail(vodId) }.getOrNull()
            ?: VodDetail(vodId = vodId)
        else if (site.isPhp()) VodDetail(vodId = vodId) // PHP 暂不支持执行
        else if (site.type.isCms()) cmsApi.detail(site, vodId)
        else if (site.isPy()) runCatching { pyEngine?.detail(vodId) }.getOrNull()
            ?: VodDetail(vodId = vodId)
        else runCatching { drpyEngine?.detail(vodId) }.getOrNull() ?: VodDetail(vodId = vodId)

    /** 搜索；站源声明 searchable=0 时直接返回空，不发请求。非 CMS 站走 drpy/PY/AI 引擎。 */
    suspend fun search(site: Site, wd: String): List<Vod> {
        if (!site.searchable || wd.isBlank()) return emptyList()
        return if (site.isAi()) runCatching { AiSourceManager.search(wd) }.getOrNull().orEmpty()
        else if (site.isPhp()) emptyList() // PHP 暂不支持执行
        else if (site.type.isCms()) cmsApi.search(site, wd)
        else if (site.isPy()) runCatching { pyEngine?.search(wd) }.getOrNull().orEmpty()
        else runCatching { drpyEngine?.search(wd) }.getOrNull().orEmpty()
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

    /** AI 播放：js/py 走各自引擎；json 走 CMS（直链已在详情里），返回空串。 */
    suspend fun aiPlay(flag: String, id: String): String =
        runCatching { AiSourceManager.playUrl(flag, id) }.getOrNull().orEmpty()

    /** PHP 播放：手机端无 PHP 解释器，暂不支持，返回空串。 */
    suspend fun phpPlay(flag: String, id: String): String = ""

    /** TVBox type=3（或 api 以 .py 结尾）是 PY 规则站。 */
    private fun Site.isPy(): Boolean = type == 3 || api.trim().endsWith(".py", ignoreCase = true)

    /** AiSourceManager 合成站源：key 前缀 "ai:"（见 AiSourceManager.activeSite）。 */
    private fun Site.isAi(): Boolean = key.startsWith("ai:", ignoreCase = true)

    /** PhpSourceManager 合成站源：key 前缀 "php:"（见 PhpSourceManager.activeSite）。 */
    private fun Site.isPhp(): Boolean = key.startsWith("php:", ignoreCase = true)

    private fun Int.isCms(): Boolean = this == 0 || this == 1
}
