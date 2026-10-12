package com.movieshell.player

import android.content.Context
import android.widget.Toast
import com.movieshell.data.model.Episode
import com.movieshell.data.model.ParseConfig
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.ParseStore
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SubSiteEngines
import com.movieshell.data.repo.SubSiteRecord
import com.movieshell.data.repo.SubSiteStore

/**
 * 播放决策公共入口（v0.19.0）：从 DetailScreen 抽取，详情页与播放器内切线路共用。
 *
 * 给定一集返回可播地址，"" 表示暂无可播地址。磁力地址原样返回，
 * 由调用方（PlayerScreen 的 magnet 分支）决定怎么播。
 * 解析失败内部 Toast 提示，不抛异常。
 */
suspend fun resolvePlayUrl(
    context: Context,
    subSite: SubSiteRecord?,
    episode: Episode,
): String {
    if (MagnetPlayer.isMagnet(episode.url)) return episode.url
    val flag = episode.flag.ifBlank { "" }
    val epUrl = episode.url
    return runCatching {
        when {
            subSite == null ->
                if (DrpySourceManager.hasActiveRule()) DrpySourceManager.playUrl(flag, epUrl)
                else if (PySourceManager.hasActiveRule()) PySourceManager.playUrl(flag, epUrl)
                else ""
            // CMS 站：vod_play_url 里就是直链，直接播
            subSite.isCmsSite() -> epUrl
            subSite.isPySite() ->
                SubSiteEngines.pyEngineFor(subSite)?.play(flag, epUrl).orEmpty()
            // type=4 站：GET {api}?ac=play&flag=&id= 拿直链；header 经
            // SniffPlayHeaders 透传给播放器（PlayerScreen 用 take 一次性取出）。
            // parse=1 时走解析线路自动解析，全部失败 Toast "解析失败"。
            subSite.isType4Site() -> {
                val r = SiteRepository().type4PlayWithParses(
                    subSite, flag, epUrl, effectiveParses(context, subSite), context
                )
                if (r.parse == 1 && r.url.isBlank()) {
                    Toast.makeText(context, "解析失败", Toast.LENGTH_LONG).show()
                }
                if (r.url.isNotBlank() && r.header.isNotEmpty()) {
                    SniffPlayHeaders.put(r.url, r.header)
                }
                r.url
            }
            // Java spider 站：playerContent 拿播放 JSON；parse=1 走解析线路
            subSite.isJavaSpider() -> {
                val r = SiteRepository().spiderPlayWithParses(
                    subSite, flag, epUrl, effectiveParses(context, subSite), context
                )
                if (r.parse == 1 && r.url.isBlank()) {
                    Toast.makeText(context, "解析失败", Toast.LENGTH_LONG).show()
                }
                if (r.url.isNotBlank() && r.header.isNotEmpty()) {
                    SniffPlayHeaders.put(r.url, r.header)
                }
                r.url
            }
            else ->
                SubSiteEngines.drpyEngineFor(subSite)?.play(flag, epUrl).orEmpty()
        }
    }.getOrDefault("")
}

/**
 * 生效的解析线路：用户自定义（ParseStore）优先，其次订阅顶层 parses。
 * 单一入口，详情页与播放器内重试都走这里。
 */
suspend fun effectiveParses(
    context: Context,
    subSite: SubSiteRecord,
): List<ParseConfig> {
    val subParses = SubSiteStore.findSite(context, subSite.key)
        ?.let { (subUrl, _) -> SubSiteStore.getParses(context, subUrl) }
        .orEmpty()
    return ParseStore.merged(context, subParses)
}
