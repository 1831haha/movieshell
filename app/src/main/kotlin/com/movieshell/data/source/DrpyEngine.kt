package com.movieshell.data.source

import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail

/**
 * drpy JS 规则引擎接口。
 *
 * v0.109.0：从已删除的 DrpyBridge.kt（WebView 实现，v0.16.0 后零引用）中抽出——
 * 接口本身被 V8DrpyEngine 及各 SourceManager 广泛实现/引用，误删会导致全仓编译失败。
 *
 * drpy 规则形如 `var rule = {host, homeUrl, url, detailUrl, searchUrl,
 * 推荐(), 一级(), 二级(), 搜索(), 播放(), class_parse(), headers}`，
 * 全部是 async 函数，用 `this.input` 取渲染后的 URL，用全局 `request(url)` 发网络。
 */
interface DrpyEngine {

    /**
     * v0.187.0 H04：最近一次调用的失败原因（null = 最近一次成功、未跑过、或该实现不提供原因）。
     * 带默认实现：已有实现类（订阅池适配器、PY 适配器等）无需改动即可编译；
     * 只有 V8DrpyEngine 真正写它，DrpySourceManager 适配器透传它。
     * 调用方据此区分“源没数据”（空结果且 lastError 为 null）与“执行失败”（空结果但有原因）。
     */
    val lastError: String?
        get() = null

    /** 载入一条 drpy 规则源码。必须在主线程调用。 */
    fun loadRule(js: String)

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
