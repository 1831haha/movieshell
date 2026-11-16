package com.movieshell.player

import android.content.Context
import com.movieshell.ui.components.AppToast
import com.movieshell.domain.model.Episode
import com.movieshell.domain.model.ParseConfig
import com.movieshell.domain.model.Site
import com.movieshell.domain.model.isCmsSite
import com.movieshell.domain.model.isJavaSpider
import com.movieshell.domain.model.isPhpSite
import com.movieshell.domain.model.isPySite
import com.movieshell.domain.model.isType4Site
import com.movieshell.data.source_mgr.DrpySourceManager
import com.movieshell.core.log.LogBus
import com.movieshell.data.source.PhpCliEngine
import com.movieshell.data.source_mgr.PhpSourceManager
import com.movieshell.data.store.ParseStore
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.source_mgr.WebParseSourceManager
import com.movieshell.data.source_mgr.SiteRepository
import com.movieshell.data.source_mgr.SubSiteEngines
import com.movieshell.data.source_mgr.SubSiteStore
import com.movieshell.data.source_mgr.SpiderEngine
import com.movieshell.data.source_mgr.SubSiteRecord
import com.movieshell.data.source.AutoSniffer
import com.movieshell.data.source.ParseApi
import com.movieshell.data.source.ParseDeadLines
import com.movieshell.data.source.ParseProgressBus
import com.movieshell.data.source.PlayRoute
import com.movieshell.data.source.PlayRouting
import com.movieshell.domain.model.RawPlay
import com.movieshell.data.source.SniffConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * 播放决策公共入口（v0.19.0）：从 DetailScreen 抽取，详情页与播放器内切线路共用。
 *
 * 给定一集返回可播地址，"" 表示暂无可播地址。磁力地址原样返回，
 * 由调用方（PlayerScreen 的 magnet 分支）决定怎么播。
 * 解析失败经 ParseProgressBus 通知 UI（解析失败对话框，可手动嗅探），不抛异常。
 * v1.2.14 B片（2）：quiet=true 时静默解析——不弹 Toast、不开网页嗅探、
 * 不报 ParseProgressBus 失败、不建解析进度会话；专供下一集后台预解析，
 * 失败只返回 ""，不影响当前播放。
 */
suspend fun resolvePlayUrl(
    context: Context,
    subSite: Site?,
    episode: Episode,
    quiet: Boolean = false,
): String {
    if (MagnetPlayer.isMagnet(episode.url)) return episode.url
    val flag = episode.flag.ifBlank { "" }
    val epUrl = episode.url
    return normalizeResolvedPlayUrl(resolvePlayUrlRaw(context, subSite, flag, epUrl, quiet))
}

/** v0.294.2：原 resolvePlayUrl 主体；出口统一经 [normalizeResolvedPlayUrl] 剥「集名$」前缀。 */
private suspend fun resolvePlayUrlRaw(
    context: Context,
    subSite: Site?,
    flag: String,
    epUrl: String,
    quiet: Boolean,
): String {
    // v0.246.0：取消重抛——原来外层 runCatching 把取消当普通失败吞掉返回 ""，
    // 调用方以为只是没地址，实际是任务被取消。内层三路顺延同理。
    return try {
        when {
            subSite == null -> {
                // v0.217.0（F22）：多路同时激活时，第一路返回空地址不再定局——
                // 空了（或抛异常）顺延试下一路，三路都空才返回空。
                // v0.294.7（Bug2）：drpy 也走 RawPlay——parse/jx/header 不再丢。
                val ctx = RouteContext(context, null, flag)
                var result = ""
                if (DrpySourceManager.hasActiveRule()) {
                    val raw = safeRaw { DrpySourceManager.playRaw(flag, epUrl) }
                    if (raw.url.isNotBlank()) result = finishRaw(ctx, raw, quiet)
                }
                if (result.isBlank() && PySourceManager.hasActiveRule()) {
                    val raw = retryOnce { PySourceManager.playRaw(flag, epUrl) }
                    if (raw.url.isNotBlank()) result = finishRaw(ctx, raw, quiet)
                }
                if (result.isBlank() && PhpSourceManager.activeRuleName() != null) {
                    val raw = retryOnce { PhpCliEngine.playRaw(context, flag, epUrl) }
                    if (raw.url.isNotBlank()) result = finishRaw(ctx, raw, quiet)
                }
                if (result.isBlank() && WebParseSourceManager.hasActiveSite()) {
                    val raw = retryOnce { WebParseSourceManager.playRaw(flag, epUrl) }
                    if (raw.url.isNotBlank()) result = finishRaw(ctx, raw, quiet)
                    if (result.isBlank() && !ctx.cancelled &&
                        (epUrl.startsWith("http://", true) || epUrl.startsWith("https://", true))
                    ) {
                        val sniffed = sniffPage(ctx, epUrl, raw, quiet)
                        if (!sniffed.isNullOrBlank()) result = sniffed
                    }
                }
                if (result.isBlank() && !ctx.cancelled && !ctx.failureReported && !quiet) {
                    AppToast.show(context, "该规则未返回播放地址", long = true)
                }
                result
            }
            // CMS 站：vod_play_url 里就是直链，直接播
            // v0.21.0：防盗链兜底——CMS 直链默认带站点 Referer（经 SniffPlayHeaders 透传）
            // v0.294.7（M1）：CMS 里混着 VIP 站网页地址（v.qq.com 等）时走解析线路，失败回退原样直播
            subSite.isCmsSite() -> {
                val u = normalizeResolvedPlayUrl(epUrl)
                val ctx = RouteContext(context, subSite, flag)
                val route = PlayRouting.decide(RawPlay(url = u), flag, ctx.vipFlags(), ctx.sitePlayUrl())
                if (route !is PlayRoute.Direct && route.url.isNotBlank()) {
                    val r = execute(ctx, route, RawPlay(url = u), fromParse1 = false, quiet = quiet)
                    if (r.isNotBlank() || ctx.cancelled) return r
                }
                if (u.isNotBlank()) {
                    apiRoot(subSite.api).takeIf { it.isNotBlank() }?.let { referer ->
                        SniffPlayHeaders.putFallback(u, mapOf("Referer" to referer))
                    }
                }
                u
            }
            // PY 站：playerContent 拿直链 + header；parse/jx/playUrl 统一路由
            subSite.isPySite() -> {
                val ctx = RouteContext(context, subSite, flag)
                val vip = ctx.vipFlags()
                finishOrToast(ctx, retryOnce { SubSiteEngines.pyPlayRaw(subSite, flag, epUrl, vip) }, quiet)
            }
            // type=4 站：GET {api}?ac=play&flag=&id= 拿直链与 header
            subSite.isType4Site() -> {
                val ctx = RouteContext(context, subSite, flag)
                finishRaw(ctx, safeRaw { SiteRepository().type4Play(subSite, flag, epUrl).toRaw() }, quiet)
            }
            // PHP 站（CLI）：playerContent 拿播放 JSON
            subSite.isPhpSite() -> {
                val ctx = RouteContext(context, subSite, flag)
                finishOrToast(ctx, retryOnce {
                    PhpCliEngine.playRaw(
                        context, flag, epUrl,
                        PhpCliEngine.scriptNameForSite(subSite.key, subSite.api),
                    )
                }, quiet)
            }
            // Java spider 站：playerContent(flag, id, vipFlags) 拿播放 JSON
            subSite.isJavaSpider() -> {
                val ctx = RouteContext(context, subSite, flag)
                val vip = ctx.vipFlags()
                finishRaw(ctx, safeRaw { SpiderEngine.play(subSite, flag, epUrl, vip).toRaw() }, quiet)
            }
            else -> {
                val ctx = RouteContext(context, subSite, flag)
                val engine = SubSiteEngines.drpyEngineFor(subSite) ?: return ""
                finishRaw(ctx, safeRaw { engine.playRaw(flag, epUrl) }, quiet)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LogBus.w("Play", "取播放地址异常：${e.message}")
        ""
    }
}

/** 一次取地址的上下文：站点信息（订阅 flags / playUrl / click / timeout）+ 是否被用户取消 */
private class RouteContext(val context: Context, val subSite: Site?, val flag: String) {
    var cancelled = false
    var failureReported = false
    private var recLoaded = false
    private var rec: SubSiteRecord? = null
    private var subUrl: String? = null

    private fun loadRec() {
        if (recLoaded) return
        recLoaded = true
        val site = subSite ?: return
        runCatching { SubSiteStore.findSite(context, site.key) }.getOrNull()?.let { (u, r) ->
            subUrl = u; rec = r
        }
    }

    /** 订阅 flags（TVBox vipFlags）；没有则用默认 VIP 表 */
    fun vipFlags(): List<String> {
        loadRec()
        val fl = subUrl?.let { runCatching { SubSiteStore.getFlags(context, it) }.getOrNull() }.orEmpty()
        return fl.ifEmpty { PlayRouting.DEFAULT_VIP_FLAGS }
    }

    fun sitePlayUrl(): String { loadRec(); return rec?.playUrl.orEmpty() }
    fun siteClick(): String { loadRec(); return rec?.click.orEmpty() }
    fun siteTimeoutMs(): Long {
        loadRec()
        val t = rec?.timeout ?: 0
        return if (t > 0) t * 1000L else SniffConfig.autoSniffTimeoutMs
    }

    suspend fun parses(): List<ParseConfig> =
        if (subSite != null) effectiveParses(context, subSite) else ParseStore.merged(context, emptyList())
}

private suspend fun safeRaw(produce: suspend () -> RawPlay): RawPlay = try {
    produce()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LogBus.w("Play", "规则取播放地址异常：${e.message}")
    RawPlay()
}

/** v0.106.0：规则偶发空返回（如 JS 抖动），先重试一次 */
private suspend fun retryOnce(produce: suspend () -> RawPlay): RawPlay {
    val r = safeRaw(produce)
    if (r.url.isNotBlank()) return r
    LogBus.w("Play", "规则首次未返回播放地址，重试一次")
    return safeRaw(produce)
}

private suspend fun finishOrToast(ctx: RouteContext, raw: RawPlay, quiet: Boolean = false): String {
    if (normalizeResolvedPlayUrl(raw.url).isBlank()) {
        if (!quiet) AppToast.show(ctx.context, "该规则未返回播放地址", long = true)
        return ""
    }
    return finishRaw(ctx, raw, quiet)
}

/**
 * v0.294.7（Bug1 / H1 / H2）：规则播放结果统一收尾——按 [PlayRouting.decide] 路由：
 * 直链直接播；jx=1 / VIP 走解析线路；parse=1 非 VIP 先站内嗅探、失败回退解析线路（旧路径）；
 * playUrl 前缀（json: / parse: / http）按 TVBox 语义。规则 header 始终透传给播放器。
 */
private suspend fun finishRaw(ctx: RouteContext, raw0: RawPlay, quiet: Boolean = false): String {
    val url = normalizeResolvedPlayUrl(raw0.url)
    if (url.isBlank()) return ""
    val raw = raw0.copy(url = url, header = PlayHeaders.normalize(raw0.header))
    val route = PlayRouting.decide(raw, ctx.flag, ctx.vipFlags(), ctx.sitePlayUrl())
    if (route !is PlayRoute.Direct) LogBus.i("Play", "播放路由：${route::class.simpleName}（parse=${raw.parse} jx=${raw.jx}）")
    return execute(ctx, route, raw, fromParse1 = raw.parse == 1 || raw.jx == 1, quiet = quiet)
}

private suspend fun execute(ctx: RouteContext, route: PlayRoute, raw: RawPlay, fromParse1: Boolean, quiet: Boolean = false): String {
    val u = route.url
    if (u.isBlank()) return ""
    val result = when (route) {
        is PlayRoute.Direct -> {
            if (raw.header.isNotEmpty()) SniffPlayHeaders.put(u, raw.header)
            return u
        }
        is PlayRoute.JxList -> jxList(ctx, u, raw, quiet)
        is PlayRoute.SniffPage -> sniffPage(ctx, u, raw, quiet)
            ?: if (ctx.cancelled) "" else jxList(ctx, u, raw, quiet)
        is PlayRoute.JsonPrefix -> parseWith(ctx, listOf(ParseConfig(name = "json", url = route.api, type = 1)), u, raw, quiet)
            ?: if (ctx.cancelled) "" else jxList(ctx, u, raw, quiet)
        is PlayRoute.NamedParse -> {
            val line = ctx.parses().firstOrNull { it.name.trim().equals(route.name, ignoreCase = true) }
            (if (line != null) parseWith(ctx, listOf(line), u, raw, quiet) else null)
                ?: if (ctx.cancelled) "" else jxList(ctx, u, raw, quiet)
        }
        is PlayRoute.PrefixSniff -> {
            val target = route.prefix + u
            sniffPage(ctx, target, raw, quiet) ?: if (ctx.cancelled) "" else jxList(ctx, u, raw, quiet)
        }
    }
    if (result.isNotBlank() || ctx.cancelled) return result
    // parse=0 推断出的路由全失败：回退原样直播（旧行为）
    if (!fromParse1) {
        if (raw.header.isNotEmpty()) SniffPlayHeaders.put(u, raw.header)
        return u
    }
    if (!quiet) {
        ctx.failureReported = true
        ParseProgressBus.reportFailure(u)
    }
    return ""
}

private suspend fun jxList(ctx: RouteContext, u: String, raw: RawPlay, quiet: Boolean = false): String {
    val parses = ctx.parses()
    if (parses.isEmpty()) {
        LogBus.w("Play", "没有可用的解析线路")
        return ""
    }
    return parseWith(ctx, parses, u, raw, quiet).orEmpty()
}

private suspend fun parseWith(ctx: RouteContext, parses: List<ParseConfig>, u: String, raw: RawPlay, quiet: Boolean = false): String? {
    val api = ParseApi()
    val hit = api.parseDetailed(parses, u, ctx.context, ctx.flag, showProgress = !quiet)
    if (api.cancelledByUser) ctx.cancelled = true
    hit ?: return null
    // v1.2.8：记下这次命中的解析线路（按结果地址），播失败时按地址反查记死
    val lineUrl = parses.firstOrNull {
        ParseDeadLines.normLineName(it.name) == ParseDeadLines.normLineName(hit.line)
    }?.url.orEmpty()
    ParseDeadLines.noteHit(u, hit.url, hit.line, lineUrl)
    // v0.186.0：解析结果带上原规则头（解析线路自己的头优先）
    if (raw.header.isNotEmpty()) SniffPlayHeaders.putFallback(hit.url, raw.header)
    return hit.url
}

/** 站内页嗅探（H4）：后台 WebView 抓真实流；可在进度条上取消。 */
private suspend fun sniffPage(ctx: RouteContext, page: String, raw: RawPlay, quiet: Boolean = false): String? {
    // v1.2.14 B片（2）：静默预解析不走网页嗅探——后台开 WebView 抓流又重又会弹
    // 解析进度/失败 UI；嗅探型集数预解析直接失败，等用户真切集时再走正常链路。
    if (quiet) return null
    // v0.294.9：苹果CMS 播放页免嗅——源码里直接有 player_aaaa 配置时不开 WebView
    val mac = runCatching { com.movieshell.data.source.MacCmsPlayer.fetch(page, raw.header) }
        .onFailure { if (it is CancellationException) throw it }.getOrNull()
    if (!mac.isNullOrBlank()) {
        when {
            PlayRouting.isVipUrl(mac) -> {
                val r = jxList(ctx, mac, raw, quiet)
                if (r.isNotBlank() || ctx.cancelled) return r.ifBlank { null }
            }
            PlayRouting.isMediaUrl(mac) -> {
                val h = PlayHeaders.merge(raw.header, mapOf("Referer" to page))
                SniffPlayHeaders.put(mac, h)
                return mac
            }
            else -> LogBus.d("Play", "免嗅地址不是直链，继续网页嗅探：${mac.take(80)}")
        }
    }
    val session = ParseProgressBus.begin(listOf(ParseConfig(name = "网页嗅探", url = page, type = 0)))
    ParseProgressBus.onLineStart(session, session.lines.first())
    val hit = try {
        coroutineScope {
            val job = async {
                AutoSniffer.sniff(
                    ctx.context, page, raw.header,
                    timeoutMs = ctx.siteTimeoutMs(), click = ctx.siteClick(), label = "网页嗅探",
                )
            }
            select<AutoSniffer.Hit?> {
                job.onAwait { it }
                session.control.onAwait {
                    job.cancel()
                    if (it is ParseProgressBus.Control.Cancel) ctx.cancelled = true
                    null
                }
            }
        }
    } finally {
        ParseProgressBus.end(session)
    }
    hit ?: return null
    SniffPlayHeaders.put(hit.url, hit.headers)
    if (raw.header.isNotEmpty()) SniffPlayHeaders.putFallback(hit.url, raw.header)
    return hit.url
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
