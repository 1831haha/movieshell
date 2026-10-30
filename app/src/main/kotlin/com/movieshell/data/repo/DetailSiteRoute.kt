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
 * 现在站点只跟路由走（Q3 起全局 holder 已删除）：
 * - 进详情的各入口（首页、搜索、演员、追剧、继续观看、通知）都
 *   在详情路由里带 siteKey 参数；路由参数存在返回栈条目里，
 *   返回栈与进程被杀恢复都原样带回，不依赖任何进程内全局变量；
 * - 详情页按 siteKey 解析站点；
 * - 本地规则站（drpy/PY/PHP）带空串，详情走当前激活规则，
 *   与原来“看详情先切激活规则”的行为一致；路由里缺省 siteKey
 *   时默认值也是空串，按本地规则站处理；
 * - 播放页（嵌入/独立全屏）的站点同样由参数传入：嵌入时详情页
 *   直接传解析结果，独立播放路由带 site 参数，再按本对象解析。
 */
object DetailSiteRoute {

    /** 站点转路由参数：订阅站与 CMS 站带 key，本地规则站带空串。 */
    fun encode(site: Site?): String = when {
        site == null -> ""
        site.isSubSite() -> site.key
        site.key.startsWith("cms:") -> site.key
        else -> ""
    }

    /** 按 siteKey 解析站点：订阅站查订阅缓存，CMS 站按 key 里的地址重建。 */
    fun resolve(context: Context, siteKey: String?): Site? {
        if (siteKey.isNullOrBlank()) return null
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
