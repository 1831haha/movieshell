package com.movieshell.player

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * 播放器路由：player?url={url}&title={title}
 *
 * url/title 在构造路由时必须经过 [Uri.encode]，
 * 否则地址中的 & ? # 等字符会破坏参数解析。
 */
const val PLAYER_ROUTE_BASE = "player"

/** 构造播放器路由（自动做 Uri.encode） */
fun playerRoute(url: String, title: String): String =
    "$PLAYER_ROUTE_BASE?url=${Uri.encode(url)}&title=${Uri.encode(title)}"

/** 跳转到播放器页 */
fun NavController.navigateToPlayer(url: String, title: String) {
    navigate(playerRoute(url, title))
}

/** 在 NavHost DSL 中注册播放器目的地 */
fun NavGraphBuilder.playerDestination(onBack: () -> Unit) {
    composable(
        route = "$PLAYER_ROUTE_BASE?url={url}&title={title}",
        arguments = listOf(
            navArgument("url") { type = NavType.StringType; defaultValue = "" },
            navArgument("title") { type = NavType.StringType; defaultValue = "" },
        ),
    ) { entry ->
        PlayerScreen(
            url = entry.arguments?.getString("url").orEmpty(),
            title = entry.arguments?.getString("title").orEmpty(),
            onBack = onBack,
        )
    }
}
