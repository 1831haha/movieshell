package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.Site
import com.movieshell.data.model.isSubSite

/**
 * 详情页站点路由参数（B1 修复的单一入口）。
 *
 * 原来详情页站点靠全局 DetailSiteHolder 跨页传参：详情 A（站 X）
 * 进演员页再进详情 B（站 Y），返回两次回到 A 时 A 的组合已销毁，
 * remember 重新读到的已是 Y；进程被杀恢复后 holder 更是 null。
 * 现在站点跟路由走：
 * - 进详情的各入口（首页、搜索、演员、追剧、继续观看、通知）都
 *   在详情路由里带 siteKey 参数；
 * - 详情页按 siteKey 解析站点，不再以 holder 的旧值为准；
 * - 本地规则站（drpy/PY/PHP）不带站参数，详情走当前激活规则，
 *   与原来“看详情先切激活规则”的行为一致。
 * - 只有路由里完全没有 siteKey 参数的老路由（已发出的旧通知、
 *   旧深链）才回退到 DetailSiteHolder，作兼容兜底；新路由一律
 *   带 siteKey，即使是空字符串，详情页据此区分新旧，不会把
 *   本地规则详情误读成上一个订阅站。
 * - DetailSiteHolder 本身暂不删除：播放页与字幕模块仍从它读
 *   当前详情站，详情页解析出站点后会把它同步成解析结果，
 *   它从“跨页传参的输入”降级为“详情页解析结果的派生缓存”。
 */
object DetailSiteRoute {
    /** 路由里没有 siteKey 参数时的哨兵：只有老路由会落到这个值。 */
    const val UNSET = "detail-site-unset"

    /** 站点转路由参数：订阅站与 CMS 站带 key，本地规则站带空串。 */
    fun encode(site: Site?): String = when {
        site == null -> ""
        site.isSubSite() -> site.key
        site.key.startsWith("cms:") -> site.key
        else -> ""
    }

    /** 按 siteKey 解析站点：订阅站查订阅缓存，CMS 站按 key 里的地址重建。 */
    fun resolve(context: Context, siteKey: String?): Site? {
        if (siteKey.isNullOrBlank() || siteKey == UNSET) return null
        if (siteKey.startsWith("sub:")) {
            return SubSiteStore.findSite(context, siteKey)
                ?.let { (subUrl, rec) -> SubSiteStore.toSite(subUrl, rec) }
        }
        if (siteKey.startsWith("cms:")) {
            val url = siteKey.removePrefix("cms:")
            if (url.isBlank()) return null
            return Site(key = siteKey, name = url, type = 1, api = url, subUrl = url)
        }
        return null
    }
}
