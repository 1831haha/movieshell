package com.movieshell.ui.search

import android.content.Context
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod as ModelVod
import com.movieshell.data.model.isSubSite
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.ui.data.Vod
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.88.0：搜索链路共享（从 SearchScreen 抽出，搜索页/演员作品页共用）。
 * 聚合搜索 = 接口站源（TVBox 订阅逐站展开）+ 本地 drpy / PY 激活规则合成站；
 * 网页源无搜索接口，不参与。
 */

/** drpy 影片 -> 界面 Vod */
internal fun ModelVod.toUiVod() = Vod(
    id = vodId,
    title = vodName,
    category = typeName,
    year = vodYear,
    rating = vodRemarks,
    area = "",
    actors = "",
    desc = vodContent,
    episodes = 0,
    poster = vodPic,
)

/**
 * 为聚合搜索拼 TVBox 订阅 JSON：
 * - 接口站源：ApiSourceStore 里启用的条目。先判断——能解析出 sites 数组的就是
 *   完整 TVBox 配置（SubSiteStore.ensureExpanded 下载展开），逐站搜
 *   （csp_ 等不支持的站在展开时已剔除）；无 sites 的纯 CMS 地址保持旧逻辑 type=1；
 * - 本地 drpy / PY 规则：有激活规则时各补一条合成站点（走引擎适配器）。
 */
internal suspend fun buildSearchConfigJson(context: Context): String {
    val arr = JSONArray()
    ApiSourceStore.load(context)
        .filter { it.enabled }
        .forEach { s ->
            val cache = SubSiteStore.ensureExpanded(context, s.url)
            if (cache != null) {
                // 完整 TVBox 配置：逐站（不支持的 kind 在展开时已标 unsupported，这里跳过）
                cache.sites
                    .filter { it.enabled && it.kind != SubSiteStore.KIND_UNSUPPORTED }
                    .forEach { rec ->
                        val site = SubSiteStore.toSite(cache.subUrl, rec)
                        arr.put(
                            JSONObject().apply {
                                put("key", site.key)
                                put("name", site.name)
                                put("type", site.type)
                                put("api", site.api)
                                put("searchable", if (rec.searchable) 1 else 0)
                                put("类型", site.typeLabel)
                                put("subUrl", cache.subUrl)
                            },
                        )
                    }
            } else {
                // 纯 CMS 地址（无 sites）：保持旧逻辑
                arr.put(
                    JSONObject().apply {
                        put("key", "cms:${s.url}")
                        put("name", s.name)
                        put("type", 1)
                        put("api", s.url)
                        put("subUrl", s.url)
                    },
                )
            }
        }
    // v0.24.0：被禁用的规则不参与聚合搜索（单点：SourceStateStore）
    if (SourceStateStore.activeDrpyRule() != null) {
        arr.put(
            JSONObject().apply {
                put("key", "drpy:active")
                put("name", "drpy:${DrpySourceManager.activeRuleName().orEmpty()}")
                put("type", 4)
                put("api", "drpy")
            },
        )
    }
    if (SourceStateStore.activePyRule() != null) {
        arr.put(
            JSONObject().apply {
                put("key", "py:active")
                put("name", "py:${PySourceManager.activeRuleName().orEmpty()}")
                put("type", 3)
                put("api", "active.py")
            },
        )
    }
    return JSONObject().put("sites", arr).toString()
}

/**
 * 进详情：订阅子站点 / CMS 订阅站把站点上下文带给详情页（详情/播放按站走引擎）；
 * 本地规则合成站（drpy:active / py:active）传 null，详情页走当前激活规则旧逻辑。
 */
internal fun goDetail(site: Site, vodId: String, onVodClick: (vodId: String) -> Unit) {
    DetailSiteHolder.site = when {
        site.isSubSite() -> site
        site.key.startsWith("cms:") -> site
        else -> null
    }
    onVodClick(vodId)
}
