package com.movieshell.data.source.subtitle

/**
 * 在线字幕源单接口（v0.28.0）。
 *
 * 2026-10 调研结论（写死 SubHD 的原因）：
 * - 射手网已死；字幕库（zimuku）上云锁反爬，App 直连被 JS 挑战挡掉；
 * - assrt.net 要注册拿 API token；SubtitleBest 要 API key；OpenSubtitles 要 key；
 * - subhd.tv 曾传下载要公众号解锁码，实测搜索页直连 200 可爬，下载走三步流程；
 * - subscene 被 Cloudflare 挡；迅雷字幕 API 已下线。
 * SubHD 是当年唯一免登录、有可爬搜索页的中文字幕源，故首版写死它。
 * 接口化以便后续加源或做成可配置；下载走 App 内统一链路，不经过第三方中转。
 */
interface SubtitleSource {
    val id: String
    val name: String

    /** 搜索字幕。10s 超时，失败抛异常（调用方转 Toast，不卡 UI）。 */
    suspend fun search(keyword: String): List<OnlineSubtitle>

    /**
     * 下载字幕，返回（文件内容，扩展名）。
     * 5MB 上限；压缩包自动解出第一个 srt/ass/ssa；失败抛异常。
     */
    suspend fun download(item: OnlineSubtitle): Pair<String, String>
}

/** 字幕源注册表（v0.28.0：首版只有 SubHD）。 */
object SubtitleSources {
    val all: List<SubtitleSource> = listOf(SubHdSource)
    fun default(): SubtitleSource = all.first()
}
