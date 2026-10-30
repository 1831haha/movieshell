package com.movieshell.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Q3 播放页状态进 ViewModel（由 q1q3-muse 移植到 ui-v5，第一批）：
 * 只收原来 PlayerScreen() 里纯界面覆盖层的状态——线路抽屉、字幕/弹幕/
 * 齿轮/简介/定时/片头片尾弹窗、控制条显隐、锁屏、手势提示、画中画标志。
 * 它们用 mutableStateOf 挂在 ViewModel 上，读写方式与原来 remember 完全一致
 * （界面照常重组），但不再随 PlayerScreen 组合函数重组而重建，也不混在
 * 2160 行的播放逻辑里。
 * 刻意不用 SavedStateHandle 存这批状态：进程被杀后重进播放页，弹窗与控制条
 * 不应自动重新弹出来；真正需要跨进程的播放身份（集数/线路）本来就由
 * PlaySession 持有，不在这里另起一套。播放引擎、View 引用、解析任务等
 * 状态仍留在 PlayerScreen 内，不进 ViewModel（View 与 Job 进 VM 是错的）。
 */
class PlayerUiViewModel : ViewModel() {
    var showLines by mutableStateOf(false)
    var showSubtitleDialog by mutableStateOf(false)
    var showOnlineSubtitle by mutableStateOf(false)
    var showDanmakuSettings by mutableStateOf(false)
    var showGearSheet by mutableStateOf(false)
    var showInfoDialog by mutableStateOf(false)
    var showSleepDialog by mutableStateOf(false)
    var showSkipSettings by mutableStateOf(false)
    var showServiceMissingDialog by mutableStateOf(false)
    var controlsVisible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    var gestureHint by mutableStateOf<String?>(null)
    var pipMode by mutableStateOf(false)
}
