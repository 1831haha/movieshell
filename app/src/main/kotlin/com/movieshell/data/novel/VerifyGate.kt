package com.movieshell.data.novel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v0.201.0：Cloudflare/WebView 验证通道（引擎 ↔ 界面）。
 *
 * 引擎在 IO 线程调 awaitVerify 挂起等待；界面层 VerifyGateDialog 收到请求后弹
 * 全屏 WebView，用户手动过验证后点「验证完成」，结果（HTML + 落地 URL + Cookie）
 * 回填；超时（默认 180 秒）或用户取消返回 null，规则求值按空结果处理，不崩。
 *
 * 同一时间只服务一个请求：新请求到来时旧请求的等待被取消（规则求值侧
 * runCatching 兜底，表现为该次规则返回空）。
 */
object VerifyGate {

    data class Request(val url: String, val title: String, val ua: String?)

    data class Result(val html: String, val url: String, val cookieHeader: String)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    private var deferred: CompletableDeferred<Result?>? = null

    suspend fun awaitVerify(
        url: String,
        title: String,
        ua: String?,
        timeoutMs: Long = 180_000,
    ): Result? {
        deferred?.cancel()
        val d = CompletableDeferred<Result?>()
        deferred = d
        _request.value = Request(url, title, ua)
        return try {
            withTimeoutOrNull(timeoutMs) { d.await() }
        } finally {
            if (_request.value?.url == url) _request.value = null
            if (deferred === d) deferred = null
        }
    }

    fun complete(result: Result?) {
        deferred?.complete(result)
    }
}
