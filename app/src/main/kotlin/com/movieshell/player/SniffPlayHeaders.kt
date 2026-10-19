package com.movieshell.player

/**
 * 网页嗅探 -> 播放器的请求头透传通道。
 *
 * WebSniffScreen 在 shouldInterceptRequest 命中直链时调用 [put] 存下该直链的
 * Referer / User-Agent，PlayerScreen 启动播放时用 [take] 一次性取出。
 * 走内存 side-channel 而不是 nav 路由参数，避免改动 MainActivity 的跳转逻辑。
 *
 * v0.107.0 P0-5：map 加条目上限（200，超了淘汰最旧）+ 10 分钟过期——
 * 之前消费不到的条目（解析失败/磁力分支/EngineHost 拒播）永久堆积，长会话缓慢泄漏。
 */
object SniffPlayHeaders {

    private const val MAX_ENTRIES = 200
    private const val EXPIRE_MS = 10 * 60 * 1000L

    private val lock = Any()
    private data class Entry(val headers: Map<String, String>, val at: Long)

    /** 插入序 LinkedHashMap，用于淘汰最旧条目。 */
    private val map = LinkedHashMap<String, Entry>()

    /** 存下某直链对应的请求头（覆盖旧值）。 */
    fun put(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            evictExpiredLocked(now)
            map[url] = Entry(headers, now)
            while (map.size > MAX_ENTRIES) {
                val eldest = map.keys.firstOrNull() ?: break
                map.remove(eldest)
            }
        }
    }

    /**
     * 一次性取出某直链的请求头，取后删除，避免过期脏数据影响后续播放。
     * 没有记录或已过期时返回空 map。
     */
    fun take(url: String): Map<String, String> =
        synchronized(lock) {
            val e = map.remove(url) ?: return emptyMap()
            if (System.currentTimeMillis() - e.at > EXPIRE_MS) emptyMap() else e.headers
        }

    /** 只看不取（下载等场景复用请求头，不断播放器的消费语义）；过期条目顺手清理。 */
    fun peek(url: String): Map<String, String> =
        synchronized(lock) {
            val e = map[url] ?: return emptyMap()
            if (System.currentTimeMillis() - e.at > EXPIRE_MS) {
                map.remove(url)
                emptyMap()
            } else e.headers
        }

    private fun evictExpiredLocked(now: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.at > EXPIRE_MS) it.remove()
        }
    }
}
