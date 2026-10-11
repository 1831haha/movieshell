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
 *  2. 按 site.type 把 home/category/detail/search 分发到 CmsApi（type=0/1）
 *     或 DrpyEngine（其他类型，v0.1 引擎未实现时返回空）；
 *  3. parseEpisodes() 把详情里的 vod_play_from / vod_play_url 对齐成 Episode 列表。
 */
class SiteRepository(
    private val cmsApi: CmsApi = CmsApi(),
    private val drpyEngine: DrpyEngine? = null
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

    /** 首页推荐/最新。 */
    suspend fun home(site: Site): List<Vod> =
        if (site.type.isCms()) cmsApi.home(site)
        else runCatching { drpyEngine?.home() }.getOrNull().orEmpty()

    /** 分类：Pair(分类列表, 当前分类第 pg 页)。 */
    suspend fun category(site: Site, tid: String, pg: Int): Pair<List<CategoryType>, List<Vod>> =
        if (site.type.isCms()) cmsApi.category(site, tid, pg)
        else emptyList<CategoryType>() to
            runCatching { drpyEngine?.category(tid, pg) }.getOrNull().orEmpty()

    /** 详情。 */
    suspend fun detail(site: Site, vodId: String): VodDetail =
        if (site.type.isCms()) cmsApi.detail(site, vodId)
        else runCatching { drpyEngine?.detail(vodId) }.getOrNull() ?: VodDetail(vodId = vodId)

    /** 搜索；站源声明 searchable=0 时直接返回空，不发请求。非 CMS 站走 drpy 引擎。 */
    suspend fun search(site: Site, wd: String): List<Vod> {
        if (!site.searchable || wd.isBlank()) return emptyList()
        return if (site.type.isCms()) cmsApi.search(site, wd)
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

    private fun Int.isCms(): Boolean = this == 0 || this == 1
}
