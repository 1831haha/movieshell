package com.movieshell.player

import android.content.Context
import com.movieshell.data.model.Episode
import com.movieshell.data.model.PlayLine

/**
 * 播放会话（v0.19.0）：详情页进入播放器前写入，播放器内「切线路」/「失败自动重试」时读取。
 *
 * 仿 SniffPlayHeaders 的单例模式——线路信息不走路由参数，避免把超长地址拼进路由。
 * 播放器退出时 [clear]，避免旧会话污染下一次播放。
 */
object PlaySession {
    /** 当前剧的全部线路（详情页按 Episode.flag 分组） */
    var lines: List<PlayLine> = emptyList()
        private set
    var lineIndex: Int = 0
        private set
    var episodeIndex: Int = 0
        private set
    /**
     * 详情页注入的解析器：给定一集返回可播地址（走完整解析链路）。
     * 磁力地址原样返回，由 PlayerScreen 的 magnet 分支处理。
     */
    var resolver: (suspend (Context, Episode) -> String)? = null
        private set

    fun start(
        lines: List<PlayLine>,
        lineIndex: Int,
        episodeIndex: Int,
        resolver: suspend (Context, Episode) -> String,
    ) {
        this.lines = lines
        this.lineIndex = lineIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        this.episodeIndex = episodeIndex.coerceAtLeast(0)
        this.resolver = resolver
    }

    fun currentLine(): PlayLine? = lines.getOrNull(lineIndex)

    /**
     * 播放器界面是否在前台（PlayerScreen 进入/退出时维护），
     * MainActivity.onUserLeaveHint 据此决定是否自动进画中画。
     */
    var playerVisible: Boolean = false

    fun moveTo(lineIndex: Int, episodeIndex: Int) {
        this.lineIndex = lineIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        this.episodeIndex = episodeIndex.coerceAtLeast(0)
    }

    fun clear() {
        lines = emptyList()
        lineIndex = 0
        episodeIndex = 0
        resolver = null
    }
}
