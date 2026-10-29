package com.movieshell.ui.search

import android.content.Context
import com.movieshell.data.model.Site
import com.movieshell.data.model.isPhpSite
import com.movieshell.data.model.isSubSite
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PhpCliEngine
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubSiteStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 本地规则搜索站的 key 前缀：key 里即规则文件名，引擎按文件取（见 SubSiteEngines）。 */
internal const val LOCAL_DRPY_PREFIX = "local:drpy:"
internal const val LOCAL_PY_PREFIX = "local:py:"

/**
 * v0.88.0：搜索链路共享（从 SearchScreen 抽出，搜索页/演员作品页共用）。
 * 聚合搜索 = 接口站源（TVBox 订阅逐站展开）+ 全部已启用的本地规则（drpy / PY / PHP 逐条）；
 * 网页源无搜索接口，不参与。
 * v0.190.0：本地规则不再只搜当前激活的一条——此前“所有源”里 drpy / PY
 * 各只有一条合成站（drpy:active / py:active，走当前激活规则引擎），
 * 用户手机里有多个 PY 规则时也只搜激活的那一个，这就是 189 收到反馈的根因。
 */

/**
 * v0.189.0：搜索范围的“当前源”，口径与首页一致：
 * 订阅当前源 > 本地 drpy > 本地 PY > 本地 PHP（CLI）。
 * 订阅当前源的校验与 HomeScreen.currentSubSite 同款：订阅须启用、
 * 缓存里该记录须启用且未被标不支持；任一条件不满足即不算当前源。
 * 合成站的 JSON 形态与聚合搜索里原有条目逐字一致（drpy:active /
 * py:active），PHP 此前不在聚合搜索里，这里按 SiteRepository.search 的
 * php: 分发口径补一条（key 里的规则名即脚本名），只搜当前源时可用。
 * 注意 drpy:active 的 type=4 本会撞上 type4 PHP 分发：SiteRepository.search
 * 里已按 key 先行分流到 drpy 引擎，否则聚合搜索里的 drpy 条目一直是空的。
 * 无任何当前源返回 null：调用方（SearchViewModel.doSearch）遇此直接出空结果，
 * 不走 loadFromConfig——空 sites 配置会被 repo 当成残缺订阅而保留旧站源列表，
 * 那样“只搜当前源”会在用户无感知的情况下悄悄变成全量。
 */
internal fun currentSourceSite(context: Context): Site? {
    val cur = SubSiteStore.getCurrent(context)
    if (cur != null) {
        val subEnabled =
            ApiSourceStore.load(context).find { it.url == cur.subUrl }?.enabled ?: false
        if (subEnabled) {
            val cache = SubSiteStore.getCached(context, cur.subUrl)
            val rec = cache?.sites?.firstOrNull { it.key == cur.siteKey }
            if (rec != null && rec.enabled && rec.kind != SubSiteStore.KIND_UNSUPPORTED) {
                return SubSiteStore.toSite(cur.subUrl, rec)
            }
        }
    }
    SourceStateStore.activeDrpyRule()?.let { name ->
        return Site(key = "drpy:active", name = "drpy:$name", type = 4, api = "drpy")
    }
    SourceStateStore.activePyRule()?.let { name ->
        return Site(key = "py:active", name = "py:$name", type = 3, api = "active.py")
    }
    SourceStateStore.activePhpRule()?.let { name ->
        return Site(key = "php:$name", name = "php:$name", type = 4, api = name)
    }
    return null
}

/** 当前源的展示名，null=未设置任何当前源（订阅/PHP 需配合规则名，见上）。 */
internal fun currentSourceName(context: Context): String? =
    currentSourceSite(context)?.name

/**
 * 为聚合搜索拼 TVBox 订阅 JSON：
 * - 接口站源：ApiSourceStore 里启用的条目。先判断——能解析出 sites 数组的就是
 *   完整 TVBox 配置（SubSiteStore.ensureExpanded 下载展开），逐站搜
 *   （csp_ 等不支持的站在展开时已剔除）；无 sites 的纯 CMS 地址保持旧逻辑 type=1；
 * - 本地规则（v0.190.0）：drpy / PY / PHP 各按 listRules 逐条补站点，
 *   只含 SourceStateStore 里未被禁用的规则；api 是规则文件的绝对路径
 *   （PHP 例外：api 即规则名，SiteRepository.search 按 php: key 取脚本名）。
 *   激活规则不再单列一条合成站，否则同一规则会被搜两次、出两个同名分组。
 * - v0.189.0 onlyCurrent=true：只放当前源一条（无当前源则 sites 为空，
 *   不回退全量）；订阅当前源的 JSON 形态与全量分支逐站条目一致。
 */
internal suspend fun buildSearchConfigJson(context: Context, onlyCurrent: Boolean = false): String {
    if (onlyCurrent) {
        val arr = JSONArray()
        val site = currentSourceSite(context)
        if (site != null) {
            arr.put(
                JSONObject().apply {
                    put("key", site.key)
                    put("name", site.name)
                    put("type", site.type)
                    put("api", site.api)
                    put("searchable", if (site.searchable) 1 else 0)
                    if (site.subUrl.isNotBlank()) put("subUrl", site.subUrl)
                    if (site.typeLabel.isNotBlank()) put("类型", site.typeLabel)
                },
            )
        }
        return JSONObject().put("sites", arr).toString()
    }
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
    // v0.190.0：全部本地规则逐条搜（v0.24.0：被禁用的规则不参与，单点 SourceStateStore）。
    // drpy 走引擎池（SubSiteEngines 按 local:drpy: key 取文件），PY 按 rulePath 直接调，
    // PHP 按 php: key 取脚本名走 CLI——三者都不依赖当前激活规则。
    DrpySourceManager.listRules()
        .filter { SourceStateStore.isRuleEnabled("js", it.name) }
        .forEach { rule ->
            arr.put(
                JSONObject().apply {
                    put("key", "$LOCAL_DRPY_PREFIX${rule.name}")
                    put("name", "drpy:${rule.name}")
                    put("type", 4)
                    put("api", File(StoragePaths.jsDir(context), rule.name).absolutePath)
                },
            )
        }
    PySourceManager.listRules()
        .filter { SourceStateStore.isRuleEnabled("py", it.name) }
        .forEach { rule ->
            arr.put(
                JSONObject().apply {
                    put("key", "$LOCAL_PY_PREFIX${rule.name}")
                    put("name", "py:${rule.name}")
                    put("type", 3)
                    put("api", File(StoragePaths.pyDir(context), rule.name).absolutePath)
                },
            )
        }
    PhpSourceManager.listRules()
        .filter { SourceStateStore.isRuleEnabled("php", it.name) }
        .forEach { rule ->
            arr.put(
                JSONObject().apply {
                    put("key", "php:${rule.name}")
                    put("name", "php:${rule.name}")
                    put("type", 4)
                    put("api", rule.name)
                },
            )
        }
    return JSONObject().put("sites", arr).toString()
}

/**
 * 进详情：订阅子站点 / CMS 订阅站把站点上下文带给详情页（详情/播放按站走引擎）；
 * 本地规则站（local:drpy: / local:py: / php:）先把该规则切成当前激活规则，
 * 再传 null——详情、播放、追剧、继续观看全部走现成的“当前激活规则”链路，
 * 不用为本地站另起一套详情/播放分发；不切的话，点非激活规则的结果会
 * 错走到当前激活规则上出详情、播错片。
 * 只搜当前源的合成站（drpy:active / py:active）切到自身是 no-op，同样走这条路。
 */
internal suspend fun goDetail(site: Site, vodId: String, onVodClick: (vodId: String) -> Unit): Boolean {
    // v0.271.0（T25）：Drpy 切源走 suspend 版，调用方放协程里等切完再进详情，
    // 否则详情会按旧源拉。PY/PHP 只验文件存在，不读文件，仍走同步版。
    // v0.217.0（F51）：切源失败不再进详情——原来三个 setActiveRule 的返回值
    // 全丢弃，规则文件删了还照常进详情，出的是另一个源的同名片或加载失败；
    // 失败时不改站点上下文、不回调，调用方按返回值提示。
    val ok = when {
        site.isSubSite() -> true
        site.key.startsWith("cms:") -> true
        site.key.startsWith(LOCAL_DRPY_PREFIX) ->
            DrpySourceManager.setActiveRuleAsync(site.key.removePrefix(LOCAL_DRPY_PREFIX))
        site.key.startsWith(LOCAL_PY_PREFIX) ->
            PySourceManager.setActiveRule(site.key.removePrefix(LOCAL_PY_PREFIX))
        site.isPhpSite() ->
            PhpSourceManager.setActiveRule(
                PhpCliEngine.scriptNameForSite(site.key, site.api),
            )
        else -> true
    }
    if (!ok) return false
    DetailSiteHolder.site = when {
        site.isSubSite() -> site
        site.key.startsWith("cms:") -> site
        else -> null
    }
    onVodClick(vodId)
    return true
}
