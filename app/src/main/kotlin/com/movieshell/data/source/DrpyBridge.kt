package com.movieshell.data.source

import android.content.Context
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail

/**
 * drpy JS 规则引擎接口（v0.1：只搭接口，不实现执行）。
 *
 * drpy 规则是 TVBox 生态的 JS 爬虫脚本，约定暴露：
 *   homeContent / categoryContent(tid, pg, filter, extend) /
 *   detailContent(ids) / playContent(flag, id) / searchContent(wd)
 * v0.1 的 SiteRepository 遇到非 CMS 站源（type=3/4 等）会走这个接口；
 * 在引擎真正实现前返回空结果，UI 层按"暂不支持"展示即可。
 */
interface DrpyEngine {

    /** 载入一条 drpy 规则源码（规则头含 key/name/type/api 等声明）。 */
    fun loadRule(js: String)

    /** 首页推荐。 */
    suspend fun home(): List<Vod>

    /** 分类列表。 */
    suspend fun category(tid: String, pg: Int): List<Vod>

    /** 详情。 */
    suspend fun detail(id: String): VodDetail

    /**
     * 取播放地址。
     * @return 最终可播的直链（规则内部已做嗅探/解析）。
     */
    suspend fun play(flag: String, id: String): String
}

/**
 * 基于 Android WebView 的 drpy 引擎空实现（v0.2 落地）。
 *
 * v0.2 实现路线（二选一，待定）：
 *  1. WebView JS 桥：在隐藏 WebView 里注入规则 JS，用 evaluateJavascript 调用
 *     homeContent/categoryContent/detailContent/playContent，把返回的 JSON 字符串
 *     再用 TvBoxConfigParser 同款容忍解析转成 Vod/VodDetail。优点是零新依赖、
 *     兼容 drpy 规则里对浏览器环境的隐式依赖；缺点是必须在主线程操作 WebView，
 *     且规则里的异步网络需桥接回 OkHttp。
 *  2. QuickJS（com.quickjs:quickjs-android）：纯 Kotlin/Java 侧执行 JS，无 WebView
 *     线程包袱，但规则若用到 DOM / localStorage 等浏览器 API 需要手工打垫片。
 *
 * 无论哪条路线，规则的网络请求建议统一收敛到 CmsApi 的 OkHttpClient（15s 超时、
 * 统一 UA），通过 JS 桥函数（如 fetch(url)）暴露给规则，而不是让规则自己发请求。
 */
class WebViewDrpyEngine(
    @Suppress("unused") private val context: Context
) : DrpyEngine {

    override fun loadRule(js: String) {
        TODO("v0.2：WebView JS 桥或 QuickJS 实现规则加载")
    }

    override suspend fun home(): List<Vod> {
        TODO("v0.2：调用规则 homeContent()")
    }

    override suspend fun category(tid: String, pg: Int): List<Vod> {
        TODO("v0.2：调用规则 categoryContent(tid, pg)")
    }

    override suspend fun detail(id: String): VodDetail {
        TODO("v0.2：调用规则 detailContent(ids)")
    }

    override suspend fun play(flag: String, id: String): String {
        TODO("v0.2：调用规则 playContent(flag, id)")
    }
}
