package com.movieshell.ui.detail

import com.movieshell.data.model.VodDetail

/** v0.294.41：详情页进程内缓存（全屏页返回 / 页面重建时秒开）。最多留 20 条。 */
internal object DetailCache {
    data class Entry(
        val detail: VodDetail? = null,
        val playUrl: String = "",
        val line: Int = 0,
        val ep: Int = 0,
        val title: String = "",
    )

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 20
    }

    @Synchronized fun get(key: String): Entry? = map[key]
    @Synchronized fun putDetail(key: String, d: VodDetail) { map[key] = (map[key] ?: Entry()).copy(detail = d) }
    @Synchronized fun putPlay(key: String, url: String, line: Int, ep: Int, title: String) {
        val e = map[key] ?: Entry()
        map[key] = e.copy(playUrl = url, line = line, ep = ep, title = title)
    }
}
