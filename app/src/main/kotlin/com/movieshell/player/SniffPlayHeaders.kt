package com.movieshell.player

/**
 * 网页嗅探 -> 播放器的请求头透传通道。
 *
 * WebSniffScreen 在 shouldInterceptRequest 命中直链时调用 [put] 存下该直链的
 * Referer / User-Agent，PlayerScreen 启动播放时用 [take] 一次性取出。
 * 走内存 side-channel 而不是 nav 路由参数，避免改动 MainActivity 的跳转逻辑。
 */
object SniffPlayHeaders {

    private val lock = Any()
    private val map = HashMap<String, Map<String, String>>()

    /** 存下某直链对应的请求头（覆盖旧值）。 */
    fun put(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        synchronized(lock) { map[url] = headers }
    }

    /**
     * 一次性取出某直链的请求头，取后删除，避免过期脏数据影响后续播放。
     * 没有记录时返回空 map。
     */
    fun take(url: String): Map<String, String> =
        synchronized(lock) { map.remove(url) ?: emptyMap() }
}
