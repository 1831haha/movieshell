package com.movieshell.data.source

import com.movieshell.data.source.SniffEngine.SniffKind

/**
 * v0.93：嗅探候选——各嗅探链路命中直链后不再首条即播，而是收集成列表，
 * 用户点选播放（对标海阔视界 MediaListActivity + DetectedVideoInfo、
 * DsPlayer labsniff.candidates）。
 */
data class SniffCandidate(
    /** 候选直链地址 */
    val url: String,
    /** 命中种类（候选列表里均为 DIRECT） */
    val kind: SniffKind,
    /** 发现链路：网络拦截 / JS钩子 / iframe / blob深挖 / 深度嗅探 / 嗅探测试 */
    val source: String,
    /** 发现时间戳（毫秒） */
    val foundAt: Long = System.currentTimeMillis(),
)
