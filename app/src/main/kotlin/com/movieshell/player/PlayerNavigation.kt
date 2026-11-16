package com.movieshell.player

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.movieshell.data.source_mgr.DetailSiteRoute
import com.movieshell.data.store.PlayerSettings

/**
 * 播放器路由：player?url={url}&title={title}&vod={vodId}&live={0/1}&site={siteKey}
 *
 * url/title 在构造路由时必须经过 [Uri.encode]，
 * 否则地址中的 & ? # 等字符会破坏参数解析。
 * v0.16.2：加 vod 参数（按剧取片头片尾跳过）。
 * v0.24.0：加 live 参数（直播模式：隐藏选集/线路等点播控件）。
 * Q3：加 site 参数（详情站 key，替代全局 DetailSiteHolder）；空=本地规则站/非点播入口。
 */
const val PLAYER_ROUTE_BASE = "player"

/** 构造播放器路由（自动做 Uri.encode） */
fun playerRoute(
    url: String,
    title: String,
    vodId: String? = null,
    isLive: Boolean = false,
    siteKey: String? = null,
): String =
    "$PLAYER_ROUTE_BASE?url=${Uri.encode(url)}&title=${Uri.encode(title)}&vod=${Uri.encode(vodId.orEmpty())}&live=${if (isLive) 1 else 0}&site=${Uri.encode(siteKey.orEmpty())}"

/** 跳转到播放器页；空地址直接不跳转，不再进黑屏播放器页只剩返回键 */
fun NavController.navigateToPlayer(
    url: String,
    title: String,
    vodId: String? = null,
    isLive: Boolean = false,
    siteKey: String? = null,
) {
    if (url.isBlank()) return
    navigate(playerRoute(url, title, vodId, isLive, siteKey))
}

/** 在 NavHost DSL 中注册播放器目的地 */
fun NavGraphBuilder.playerDestination(
    onBack: () -> Unit,
    // v1.1.31：libmpv 未安装时跳插件市场装插件
    onOpenPluginCenter: () -> Unit = {},
    // v1.1.31：播放失败自动换源——跳同名剧详情页同集播
    onAutoSwitchSource: ((vodId: String, siteKey: String, episodeIndex: Int) -> Unit)? = null,
) {
    composable(
        route = "$PLAYER_ROUTE_BASE?url={url}&title={title}&vod={vod}&live={live}&site={site}",
        arguments = listOf(
            navArgument("url") { type = NavType.StringType; defaultValue = "" },
            navArgument("title") { type = NavType.StringType; defaultValue = "" },
            navArgument("vod") { type = NavType.StringType; defaultValue = "" },
            navArgument("live") { type = NavType.StringType; defaultValue = "0" },
            navArgument("site") { type = NavType.StringType; defaultValue = "" },
        ),
        // v1.2.14 B片（9）：播放器转场动画按设置走（目的地级覆盖 NavHost 全局淡入+位移）；
        // 系统默认返回 null（用全局转场），淡入/缩放/海报碎片各给一对进出场。
        // 海报碎片的进场主体是 PlayerScreen 内的碎片拼贴揭示，这里只配一个
        // 快速淡入打底；退场碎片做不了（页已离场），统一淡出。
        enterTransition = {
            when (PlayerSettings.transitionMode.value) {
                PlayerSettings.TRANSITION_FADE -> fadeIn(tween(250))
                PlayerSettings.TRANSITION_SCALE ->
                    scaleIn(tween(250), initialScale = 0.96f) + fadeIn(tween(250))
                PlayerSettings.TRANSITION_SHARDS -> fadeIn(tween(120))
                else -> null
            }
        },
        popExitTransition = {
            when (PlayerSettings.transitionMode.value) {
                PlayerSettings.TRANSITION_FADE -> fadeOut(tween(200))
                PlayerSettings.TRANSITION_SCALE ->
                    scaleOut(tween(200), targetScale = 0.96f) + fadeOut(tween(200))
                PlayerSettings.TRANSITION_SHARDS -> fadeOut(tween(250))
                else -> null
            }
        },
    ) { entry ->
        val navUrl = entry.arguments?.getString("url").orEmpty()
        // v0.164.0：pics:// 图片集不进视频播放器，独立路由也分流到看图页
        if (isPicsUrl(navUrl)) {
            PicsViewerScreen(
                url = navUrl,
                title = entry.arguments?.getString("title").orEmpty(),
                onBack = onBack,
            )
        } else {
            // Q3：与详情页同口径解析站点（站已被删除时为 null，按无订阅站处理）
            val context = LocalContext.current
            val siteArg = entry.arguments?.getString("site").orEmpty()
            val playSiteKey = remember(siteArg) { DetailSiteRoute.resolve(context, siteArg)?.key }
            PlayerScreen(
                url = navUrl,
                title = entry.arguments?.getString("title").orEmpty(),
                vodId = entry.arguments?.getString("vod")?.ifBlank { null },
                siteKey = playSiteKey,
                isLive = entry.arguments?.getString("live") == "1",
                onBack = onBack,
                onOpenPluginCenter = onOpenPluginCenter,
                onAutoSwitchSource = onAutoSwitchSource,
            )
        }
    }
}
