package com.movieshell.player

import android.content.Context
import com.movieshell.data.source.EmbyApi
import com.movieshell.domain.model.Episode
import com.movieshell.domain.model.PlayLine

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
     * v1.2.14 B片（2）：quiet=true 为静默解析——不弹 Toast、不开网页嗅探、
     * 不报解析失败，专供下一集后台预解析；切集时一律 quiet=false。
     */
    var resolver: (suspend (Context, Episode, Boolean) -> String)? = null
        private set

    fun start(
        lines: List<PlayLine>,
        lineIndex: Int,
        episodeIndex: Int,
        resolver: suspend (Context, Episode, Boolean) -> String,
    ) {
        this.lines = lines
        this.lineIndex = lineIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        this.episodeIndex = episodeIndex.coerceAtLeast(0)
        this.resolver = resolver
    }

    fun currentLine(): PlayLine? = lines.getOrNull(lineIndex)

    /**
     * 播放器界面是否在前台（PlayerScreen 进入/退出时维护）。
     * v0.264.0 起按 Home 只暂停、不自动进画中画：MainActivity.onUserLeaveHint
     * 用它判断是否在播放页内、正在播，才暂停（见 MainActivity.onUserLeaveHint）。
     */
    var playerVisible: Boolean = false

    /**
     * 直播全屏回小窗标记（v0.294.51）：直播页嵌入播放器跳独立全屏页前置 true，
     * 独立页退出时若此标记仍在，说明下面就是等着接续同一播放器的直播页，
     * 此时独立页 dispose 不得停引擎、不清会话——否则小窗 effect 已先判
     * “接续同一播放器”不重拉，随后又被停掉，播放键 resume 对已 stop 的
     * Media3 无效，表现为“回小窗暂停、点播放没反应”。独立页 dispose
     * 消费一次后清掉；非直播入口（历史/下载/嗅探）从不置此标记，退出照旧停播。
     */
    var returnToEmbedded: Boolean = false

    /**
     * v1.2.13：Emby / Jellyfin 章节（片头片尾跳过用）。
     * 媒体服务器入口播前写入，播放器按进度查跳过目标；播放器退出时随 clear 清掉，
     * 非 Emby 入口从不写，旧会话不会污染下一次播放。
     */
    var chapters: List<EmbyApi.EmbyChapter> = emptyList()
        private set

    fun setChapters(list: List<EmbyApi.EmbyChapter>) {
        chapters = list
    }

    fun moveTo(lineIndex: Int, episodeIndex: Int) {
        this.lineIndex = lineIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        this.episodeIndex = episodeIndex.coerceAtLeast(0)
    }

    fun clear() {
        lines = emptyList()
        lineIndex = 0
        episodeIndex = 0
        resolver = null
        chapters = emptyList()
        // v1.3.21 L9：播放页标记也复位，否则下次独立页退出会误判“回小窗”而不停播。
        playerVisible = false
        returnToEmbedded = false
    }
}
