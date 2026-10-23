package com.movieshell.player

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * 播放器路由：player?url={url}&title={title}&vod={vodId}&live={0/1}
 *
 * url/title 在构造路由时必须经过 [Uri.encode]，
 * 否则地址中的 & ? # 等字符会破坏参数解析。
 * v0.16.2：加 vod 参数（按剧取片头片尾跳过）。
 * v0.24.0：加 live 参数（直播模式：隐藏选集/线路等点播控件）。
 */
const val PLAYER_ROUTE_BASE = "player"

/** 构造播放器路由（自动做 Uri.encode） */
fun playerRoute(url: String, title: String, vodId: String? = null, isLive: Boolean = false): String =
    "$PLAYER_ROUTE_BASE?url=${Uri.encode(url)}&title=${Uri.encode(title)}&vod=${Uri.encode(vodId.orEmpty())}&live=${if (isLive) 1 else 0}"

/** 跳转到播放器页；空地址直接不跳转，不再进黑屏播放器页只剩返回键 */
fun NavController.navigateToPlayer(
    url: String,
    title: String,
    vodId: String? = null,
    isLive: Boolean = false,
) {
    if (url.isBlank()) return
    navigate(playerRoute(url, title, vodId, isLive))
}

/** 在 NavHost DSL 中注册播放器目的地 */
fun NavGraphBuilder.playerDestination(onBack: () -> Unit) {
    composable(
        route = "$PLAYER_ROUTE_BASE?url={url}&title={title}&vod={vod}&live={live}",
        arguments = listOf(
            navArgument("url") { type = NavType.StringType; defaultValue = "" },
            navArgument("title") { type = NavType.StringType; defaultValue = "" },
            navArgument("vod") { type = NavType.StringType; defaultValue = "" },
            navArgument("live") { type = NavType.StringType; defaultValue = "0" },
        ),
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
            PlayerScreen(
                url = navUrl,
                title = entry.arguments?.getString("title").orEmpty(),
                vodId = entry.arguments?.getString("vod")?.ifBlank { null },
                isLive = entry.arguments?.getString("live") == "1",
                onBack = onBack,
            )
        }
    }
}
