package com.movieshell.data.source

import java.util.concurrent.ConcurrentHashMap

/**
 * v0.294.9：同一页面嗅探出的备选地址（参考海阔「解析到多个视频地址会自动切换测试」）。
 * 后台嗅探命中后登记「首选 + 最多 2 个备选」；播放失败、准备换线路前，
 * 播放器先用 [next] 取同页下一个没试过的地址。纯 Kotlin，可单元测试。
 */
object SniffAlternates {

    data class Candidate(val url: String, val headers: Map<String, String>)

    private class Group(val list: List<Candidate>) {
        val tried: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    }

    private const val MAX_GROUPS = 50
    private val byUrl = ConcurrentHashMap<String, Group>()

    /** 登记一组候选（第一个是已选用的首选）；少于 2 个不登记。 */
    fun register(candidates: List<Candidate>) {
        val list = candidates.filter { it.url.isNotBlank() }.distinctBy { it.url }.take(3)
        if (list.size < 2) return
        if (byUrl.size > MAX_GROUPS * 3) byUrl.clear()
        val g = Group(list)
        list.forEach { byUrl[it.url] = g }
    }

    /** 该地址播放失败：返回同组下一个没试过的候选；没有返回 null。 */
    fun next(failedUrl: String): Candidate? {
        val g = byUrl[failedUrl] ?: return null
        g.tried.add(failedUrl)
        return g.list.firstOrNull { it.url !in g.tried }?.also { g.tried.add(it.url) }
    }

    fun clear() = byUrl.clear()
}
