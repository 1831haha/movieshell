package com.movieshell.ui.data

/**
 * 界面用影片数据结构（v0.188.0：原 DemoData 假数据列表已删，
 * 只留仍被首页、详情、搜索、演员页使用的 Vod 类，不要再往这里加假数据）。
 */
data class Vod(
    val id: String,
    val title: String,
    val category: String,
    val year: String,
    val rating: String,
    val area: String,
    val actors: String,
    val desc: String,
    val episodes: Int,
    val poster: String = "",
)
