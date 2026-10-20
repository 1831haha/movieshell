package com.movieshell.data.source

import androidx.javascriptengine.IsolateTerminatedException
import androidx.javascriptengine.JavaScriptConsoleCallback
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePort
import com.google.common.util.concurrent.ListenableFuture
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** ListenableFuture → 挂起等待（手写桥，不引 kotlinx-coroutines-guava）。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T> ListenableFuture<T>.awaitJs(): T =
    suspendCancellableCoroutine { cont ->
        val r = Runnable {
            try {
                val v = get()
                if (cont.isActive) cont.resume(v, null)
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }
        addListener(r, Executor { it.run() })
    }

/** JS 会话已关闭/终止：调用方应丢弃本会话并重建。 */
internal class JsSessionClosedException(message: String) : IllegalStateException(message)

/** JS 执行超时（isolate 已被终止以真停脚本）：调用方应重建会话。 */
internal class JsSessionTimeoutException(message: String) : IllegalStateException(message)

/**
 * v0.113.0 A1–A5：一个 [JsSession] = 一个 V8 [JavaScriptSandbox.createIsolate isolate]
 * + 一条与宿主之间的 MessagePort + 宿主 RPC 分发器。
 *
 * - [eval]：evaluateJavaScriptAsync 挂起等待；表达式返回 Promise 时 Future 在 promise
 *   结算后才完成（JS_FEATURE_PROMISE_RETURN）——JS 侧 `await` 即事件循环，宿主网络
 *   请求完全不占用 JS 执行路径（A2 的根治）。
 * - 超时真中断（A4）：withTimeoutOrNull 到点后直接 close isolate。isolate 跑在 WebView
 *   提供的独立进程里，同步死循环不会卡宿主任何线程；close 终止上下文，在途 evaluate
 *   以 IsolateTerminatedException 立即失败——比 QuickJS 的"线程卡死只能隔离"干净。
 * - RPC：JS 侧 `__hrpc({kind,...})` 经 port 发给宿主，宿主在 IO 协程执行注册的 handler
 *   （如 OkHttp 异步请求），完成后 evaluateJavaScriptAsync 注入 `__hrpcResolve(id,ok,b64)`
 *   结算 JS 侧 Promise。全程无 JS 线程阻塞。
 * - isolate 不可跨 JS 状态共享，但对象本身 thread-safe：eval 可在任意协程调用。
 */
internal class JsSession(private val logTag: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var isolate: JavaScriptIsolate? = null
    private var port: MessagePort? = null

    /** kind → 宿主 RPC handler（收到 {id,kind,...} 后异步执行，结果回注 __hrpcResolve）。 */
    private val rpcHandlers = ConcurrentHashMap<String, suspend (JSONObject) -> String>()

    /** 无 id 的单向事件（如 LX 的 send('inited', payload)）。 */
    @Volatile
    var onEvent: ((kind: String, value: String) -> Unit)? = null

    /** 引导失败原因（bootstrap 返回 false 时读取）。 */
    @Volatile
    var lastError: String = ""
        private set

    fun registerRpc(kind: String, handler: suspend (JSONObject) -> String) {
        rpcHandlers[kind] = handler
    }

    /**
     * 创建 isolate 并依次 evaluate [scripts]（host.js 垫片、业务 shim、规则源码），
     * 最后建立 MessagePort（JS 侧 host.js 按 [portName] 经 android.getNamedPort 接住）。
     * 任一步失败：关闭 isolate，返回 false（lastError 带原因）。
     */
    suspend fun bootstrap(portName: String, scripts: List<String>): Boolean {
        val sandbox = JsRuntime.sandbox() ?: run {
            lastError = "V8 sandbox 不可用（设备不支持或能力缺失）"
            return false
        }
        val iso = try {
            sandbox.createIsolate()
        } catch (e: Throwable) {
            lastError = "isolate 创建失败: ${e.message}"
            return false
        }
        synchronized(lock) { isolate = iso }
        iso.setConsoleCallback(Executor { it.run() }, object : JavaScriptConsoleCallback {
            override fun onConsoleMessage(message: JavaScriptConsoleCallback.ConsoleMessage) {
                val text = "V8: ${message.message}"
                when (message.level) {
                    JavaScriptConsoleCallback.ConsoleMessage.LEVEL_ERROR -> LogBus.e(logTag, text)
                    JavaScriptConsoleCallback.ConsoleMessage.LEVEL_WARNING -> LogBus.w(logTag, text)
                    else -> LogBus.d(logTag, text)
                }
            }
        })
        // 先建 port 再跑脚本：脚本首次 __hrpc 时 port 必已就绪
        val p = try {
            iso.createMessageChannel(portName, Executor { it.run() }) { msg -> onPortMessage(msg) }
        } catch (e: Throwable) {
            lastError = "MessagePort 建立失败: ${e.message}"
            LogBus.w(logTag, lastError)
            closeIsolate()
            return false
        }
        synchronized(lock) { port = p }
        for (script in scripts) {
            try {
                iso.evaluateJavaScriptAsync(script).awaitJs()
            } catch (e: Throwable) {
                lastError = "JS 引导失败: ${e.message}"
                LogBus.w(logTag, lastError)
                closeIsolate()
                return false
            }
        }
        return true
    }

    /** evaluate 一次 JS。超时/会话已死分别抛 [JsSessionTimeoutException]/[JsSessionClosedException]。 */
    suspend fun eval(expression: String, timeoutMs: Long): String {
        val iso = synchronized(lock) { isolate } ?: throw JsSessionClosedException("JS 会话已关闭")
        val result = try {
            withTimeoutOrNull(timeoutMs) {
                iso.evaluateJavaScriptAsync(expression).awaitJs()
            }
        } catch (e: ExecutionException) {
            // isolate 已终止（close/崩溃）：在途 evaluate 以此失败
            if (e.cause is IsolateTerminatedException) {
                throw JsSessionClosedException("isolate 已终止")
            }
            throw e
        } catch (e: IsolateTerminatedException) {
            throw JsSessionClosedException("isolate 已终止")
        }
        if (result == null) {
            closeIsolate()
            throw JsSessionTimeoutException("JS 执行超时(${timeoutMs}ms)，isolate 已终止")
        }
        return result
    }

    /** 关闭 isolate：在途 evaluate 立即失败，后续 eval 抛 [JsSessionClosedException]。 */
    fun close() = closeIsolate()

    private fun closeIsolate() {
        val p = synchronized(lock) { port.also { port = null } }
        val iso = synchronized(lock) { isolate.also { isolate = null } }
        // 在途 RPC handler（网络请求/resolve 注入）随会话终止，不留孤儿协程
        runCatching { scope.cancel() }
        runCatching { p?.close() }
        // 对已终止的 isolate close 会抛 IsolateTerminatedException：吞掉，杀上下文的目的已达成
        runCatching { iso?.close() }
            .onFailure { LogBus.d(logTag, "isolate close: ${it.message}") }
    }

    /** 宿主侧 port 收消息：{id,kind,...}=RPC 请求；{kind,value}=单向事件。 */
    private fun onPortMessage(message: Message) {
        if (message.type != Message.TYPE_STRING) return
        val text = message.string ?: return
        val m = try {
            JSONObject(text)
        } catch (e: Exception) {
            LogBus.w(logTag, "port 消息解析失败: ${e.message}")
            return
        }
        val kind = m.optString("kind")
        val id = m.optLong("id", -1L)
        if (id >= 0) {
            val handler = rpcHandlers[kind]
            scope.launch {
                val (ok, result) = if (handler == null) {
                    false to "no rpc handler: $kind"
                } else {
                    try {
                        true to handler(m)
                    } catch (e: Throwable) {
                        LogBus.w(logTag, "RPC $kind 失败: ${e.message}")
                        false to (e.message ?: "rpc failed")
                    }
                }
                // 结果以 base64(UTF-8) 传输，避免把任意字符串转义进表达式字面量
                val b64 = Base64.encodeToString(result.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val iso = synchronized(lock) { isolate } ?: return@launch
                try {
                    iso.evaluateJavaScriptAsync("__hrpcResolve(${id}, $ok, ${JSONObject.quote(b64)})").awaitJs()
                } catch (e: Throwable) {
                    LogBus.d(logTag, "RPC resolve 注入失败: ${e.message}")
                }
            }
        } else {
            onEvent?.invoke(kind, m.optString("value"))
        }
    }

    companion object {
        private val seq = AtomicLong()

        /** 为新会话生成一个进程内唯一的 port 名（isolate 侧 namespace 独立，前缀仅便于日志辨识）。 */
        fun newPortName(): String = "ms-js-${seq.incrementAndGet()}"
    }
}
