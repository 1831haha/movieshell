package com.movieshell.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * v0.106.0：播放解析专用应用级 scope。
 *
 * 根因：解析任务（ParseApi 多条线路 / PY playerContent / type=4）在 DetailScreen /
 * PlayerScreen 的 rememberCoroutineScope 里启动，用户切页、返回或打开播放器时
 * composition 离开 → scope 被取消 → "The coroutine scope left the composition"，
 * 解析服务明明已返回 200 也白搭（用户 13:11 日志：i275.com 16 条线路全灭）。
 * 跟 v0.103.0 音乐搜索中断同类病。
 *
 * 改：解析拿地址走应用级 scope（SupervisorJob：各子任务互不取消），切页/切后台
 * 不中断；UI 状态回写仍在主线程。注意只用于"解析拿地址"——播放器自身的起播
 * 仍跟随 PlayerScreen composition（离开播放器就该停，不在这里）。
 */
object PlayResolveScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
