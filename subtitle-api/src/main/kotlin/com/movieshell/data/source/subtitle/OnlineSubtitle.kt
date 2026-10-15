package com.movieshell.data.source.subtitle

/** 在线字幕搜索结果。 */
data class OnlineSubtitle(
    val sourceId: String,
    val sid: String,
    val title: String,
    val language: String = "",
    val format: String = "",
    val size: String = "",
    val downloads: String = "",
)
