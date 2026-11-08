package com.movieshell.data.source

import com.movieshell.domain.model.CategoryType
import com.movieshell.domain.model.RawPlay
import com.movieshell.domain.model.Site
import com.movieshell.domain.model.Vod
import com.movieshell.domain.model.VodDetail
import com.movieshell.domain.source.SourceEngine

/**
 * v1.1.5 分层第 1 批：Type4（PHP 苹果 CMS）站绑定成 SourceEngine。
 *
 * Type4Api 是无站状态的共享单例（方法都带 Site 参数）；本适配器把一个站绑死，
 * 调用方按 SourceEngine 用，不用再传 Site。共享实例必须由调用方传入——
 * lastErrorBySite 是实例状态，新建实例会丢失败原因；typeCache/modeB 在伴生对象里共享。
 * 筛选：Type4 无 filter 概念，category() 忽略 filter 参数；filters() 默认空列表。
 */
class Type4SourceEngine(
    private val site: Site,
    private val api: Type4Api,
) : SourceEngine {

    override val lastError: String?
        get() = api.lastErrorFor(site.key)

    override suspend fun home(): List<Vod> = api.home(site)

    override suspend fun categories(): List<CategoryType> =
        api.category(site, "", 1).first

    override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> =
        api.category(site, tid, pg).second

    override suspend fun detail(id: String): VodDetail = api.detail(site, id)

    override suspend fun search(wd: String): List<Vod> = api.search(site, wd)

    override suspend fun play(flag: String, id: String): String =
        api.play(site, flag, id).url

    override suspend fun playRaw(flag: String, id: String): RawPlay =
        api.play(site, flag, id).toRaw()

    override fun release() = Unit
}
