package com.movieshell.ui.music

import androidx.navigation.NavHostController

/**
 * v0.294.17：播放页进出的唯一入口。
 *
 * 用户反馈「返回点两三次，返回不到音乐主界面」：原来迷你条、通知栏、音源管理页上的
 * 迷你条都直接 `navigate("music_player")`，没有 launchSingleTop，也不查栈里是否已有播放页——
 * 双击迷你条、在播放页点通知、播放页→换源→管理音源→再点迷你条，每次都再压一层播放页，
 * 返回要按好几次；返回箭头连点又会多弹一层。规则：
 * - 已在播放页：什么都不做；
 * - 栈里已有播放页（例如在它上面开了音源管理）：弹回那一层，不新建；
 * - 否则：singleTop 新开一层。
 * 关闭只在当前确实是播放页时弹一次（连点/返回手势与箭头叠加不会多弹）。
 */
internal const val MUSIC_PLAYER_ROUTE = "music_player"

internal enum class PlayerNavAction { NONE, POP_TO_EXISTING, NAVIGATE }

/** 纯逻辑：给定当前路由与栈内是否已有播放页，决定打开播放页的动作（单测覆盖）。 */
internal fun decideOpenPlayer(currentRoute: String?, playerInBackStack: Boolean): PlayerNavAction = when {
    currentRoute == MUSIC_PLAYER_ROUTE -> PlayerNavAction.NONE
    playerInBackStack -> PlayerNavAction.POP_TO_EXISTING
    else -> PlayerNavAction.NAVIGATE
}

/** 纯逻辑：返回键/箭头是否应弹栈（只在当前就是播放页时弹，防双弹）。 */
internal fun shouldPopPlayer(currentRoute: String?): Boolean = currentRoute == MUSIC_PLAYER_ROUTE

internal fun NavHostController.openMusicPlayer() {
    val inStack = runCatching { getBackStackEntry(MUSIC_PLAYER_ROUTE) }.isSuccess
    when (decideOpenPlayer(currentBackStackEntry?.destination?.route, inStack)) {
        PlayerNavAction.NONE -> Unit
        PlayerNavAction.POP_TO_EXISTING -> popBackStack(MUSIC_PLAYER_ROUTE, inclusive = false)
        PlayerNavAction.NAVIGATE -> navigate(MUSIC_PLAYER_ROUTE) { launchSingleTop = true }
    }
}

internal fun NavHostController.closeMusicPlayer() {
    if (shouldPopPlayer(currentBackStackEntry?.destination?.route)) popBackStack()
}
