package com.movieshell.data.source.subtitle

/**
 * 在线字幕源插件接口。
 *
 * 主 App 只依赖此接口，具体实现（如 SubHD）由插件提供，
 * 经 DexClassLoader 动态加载（见 SubtitlePluginLoader）。
 *
 * 2026-10 调研结论（为什么首版是 SubHD）：
 * - 射手网已死；字幕库上云锁反爬；assrt 要注册；OpenSubtitles/subscene 要 key 或被墙；
 * - SubHD 是唯一免登录、有可爬搜索页的中文字幕源。
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
