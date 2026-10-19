package com.movieshell.player

import android.content.Context
import android.widget.Toast
import com.movieshell.data.model.Episode
import com.movieshell.data.model.ParseConfig
import com.movieshell.data.model.Site
import com.movieshell.data.model.isCmsSite
import com.movieshell.data.model.isJavaSpider
import com.movieshell.data.model.isPySite
import com.movieshell.data.model.isType4Site
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.ParseStore
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SubSiteEngines
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.source.ParseApi

/**
 * 播放决策公共入口（v0.19.0）：从 DetailScreen 抽取，详情页与播放器内切线路共用。
 *
 * 给定一集返回可播地址，"" 表示暂无可播地址。磁力地址原样返回，
 * 由调用方（PlayerScreen 的 magnet 分支）决定怎么播。
 * 解析失败内部 Toast 提示，不抛异常。
 */
suspend fun resolvePlayUrl(
    context: Context,
    subSite: Site?,
    episode: Episode,
): String {
    if (MagnetPlayer.isMagnet(episode.url)) return episode.url
    val flag = episode.flag.ifBlank { "" }
    val epUrl = episode.url
    return runCatching {
        when {
            subSite == null ->
                if (DrpySourceManager.hasActiveRule()) DrpySourceManager.playUrl(flag, epUrl)
                else if (PySourceManager.hasActiveRule())
                    finishPyPlay(context, null) { PySourceManager.playRaw(flag, epUrl) }
                else ""
            // CMS 站：vod_play_url 里就是直链，直接播
            // v0.21.0：防盗链兜底——CMS 直链默认带站点 Referer（经 SniffPlayHeaders 透传）
            subSite.isCmsSite() -> {
                if (epUrl.isNotBlank()) {
                    apiRoot(subSite.api).takeIf { it.isNotBlank() }?.let { referer ->
                        SniffPlayHeaders.put(epUrl, mapOf("Referer" to referer))
                    }
                }
                epUrl
            }
            // PY 站：playerContent 拿直链 + header；parse=1 时走解析线路（与 Java spider 一致）
            subSite.isPySite() ->
                finishPyPlay(context, subSite) { SubSiteEngines.pyPlayRaw(subSite, flag, epUrl) }
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
 * v0.27.1：PY 规则 playerContent 结果统一收尾。
 * - header（Referer/UA）经 SniffPlayHeaders 透传给播放器，防盗链站点必需；
 * - parse=1 时用解析线路拿直链，全部失败 Toast"解析失败"。
 */
private suspend fun finishPyPlay(
    context: Context,
    subSite: Site?,
    produce: suspend () -> Triple<String, Int, Map<String, String>>,
): String {
    // v0.106.0：规则偶发空返回（如 JS 抖动），先重试一次；仍空则明确 Toast，
    // 不再静默返回 ""（用户看到的就是"点了播不出来"，无从查起）
    var r = produce()
    if (r.first.isBlank()) {
        LogBus.w("Play", "PY 规则首次未返回播放地址，重试一次")
        r = runCatching { produce() }.getOrDefault(Triple("", 0, emptyMap()))
    }
    val (url, parse, header) = r
    if (url.isBlank()) {
        Toast.makeText(context, "该规则未返回播放地址", Toast.LENGTH_LONG).show()
        return ""
    }
    if (header.isNotEmpty()) SniffPlayHeaders.put(url, header)
    if (parse != 1) return url
    val parses = if (subSite != null) effectiveParses(context, subSite)
    else ParseStore.merged(context, emptyList())
    val direct = ParseApi().parse(parses, url, context)
    if (direct.isBlank()) {
        Toast.makeText(context, "解析失败", Toast.LENGTH_LONG).show()
        return ""
    }
    return direct
}

/**
 * 生效的解析线路：用户自定义（ParseStore）优先，其次订阅顶层 parses。
 * 单一入口，详情页与播放器内重试都走这里。
 */
suspend fun effectiveParses(
    context: Context,
    subSite: Site,
): List<ParseConfig> {
    val subParses = SubSiteStore.findSite(context, subSite.key)
        ?.let { (subUrl, _) -> SubSiteStore.getParses(context, subUrl) }
        .orEmpty()
    return ParseStore.merged(context, subParses)
}

/**
 * v0.21.0：站点 API 地址取根（如 https://example.com/api.php/provide/vod → https://example.com/），
 * 做 CMS 直链的默认 Referer（防盗链兜底）。解析不出返回 ""。
 */
private fun apiRoot(api: String): String {
    val noQuery = api.substringBefore("?").trim()
    if (!noQuery.contains("://")) return ""
    val scheme = noQuery.substringBefore("://")
    val host = noQuery.substringAfter("://").substringBefore("/")
    if (host.isBlank()) return ""
    return "$scheme://$host/"
}
