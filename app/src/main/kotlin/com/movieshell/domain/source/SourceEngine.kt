package com.movieshell.domain.source

import com.movieshell.domain.model.CategoryType
import com.movieshell.domain.model.FilterGroup
import com.movieshell.domain.model.Vod
import com.movieshell.domain.model.VodDetail

/**
 * v1.1.4 分层第 0 批：源引擎的领域接口（先只定义，不接线）。
 *
 * 方法名与签名照抄 data/source/DrpyEngine 的真实接口（不是文档初稿里的 PY 式
 * homeContent/categoryContent/playerContent——那是 Python Spider 的方法名，
 * Kotlin 侧一直叫 home/category/detail/search/play）。
 * 第 1 批再让 DrpyEngine / PyApi / Type4Api 实现它；playRaw（带 parse/jx 的
 * 完整播放结果）返回 data/source 的 RawPlay，暂不收入本接口。
 */
interface SourceEngine {

    /**
     * 最近一次调用的失败原因（null = 最近一次成功、未跑过、或该实现不提供原因）。
     * 调用方据此区分"源没数据"（空结果且 lastError 为 null）与"执行失败"（空结果但有原因）。
     */
    val lastError: String?
        get() = null

    /** 首页推荐。 */
    suspend fun home(): List<Vod>

    /** 全部分类（来自 rule.class_parse 或 class_name/class_url）。 */
    suspend fun categories(): List<CategoryType>

    /**
     * 分类列表。
     * @param filter 筛选键→选中值（对应规则 filter_url 里 {{fl.键名}} 的键名）；
     * 空 map 时引擎按规则默认值渲染（filter_def[tid]，再取 filter 分组首项）。
     */
    suspend fun category(tid: String, pg: Int, filter: Map<String, String> = emptyMap()): List<Vod>

    /**
     * 该分类的筛选定义（drpy filterable 规则的 filter 分组）。
     * 规则没配 filter / 非 drpy 引擎时返回空列表；UI 筛选器据此绘制。
     */
    suspend fun filters(tid: String): List<FilterGroup> = emptyList()

    /** 详情。 */
    suspend fun detail(id: String): VodDetail

    /** 搜索。 */
    suspend fun search(wd: String): List<Vod>

    /**
     * 取播放地址。
     * @return 最终可播的直链（规则内部已做嗅探/解析）。
     */
    suspend fun play(flag: String, id: String): String

    /** 销毁引擎，释放资源。 */
    fun release()
}
