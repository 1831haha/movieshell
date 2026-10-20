package com.movieshell.data.source

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import com.movieshell.data.repo.LogBus

/**
 * v0.113.0 A1–A5：进程级 V8 sandbox 单例（androidx.javascriptengine）。
 *
 * 背景：app.cash.quickjs 的 evaluate 是同步 JNI，request() 只能在 JS 线程阻塞执行，
 * 且超时无法中断 native 调用（A1–A5 的根因）。javascriptengine 由设备 WebView 提供
 * V8 isolate，`evaluateJavaScriptAsync` 对返回 Promise 的表达式会在 promise 结算后
 * 才完成——JS 里的 `await` 就是天然事件循环，宿主网络请求完全移出 JS 执行路径。
 *
 * 约束（官方文档）：
 * - 每进程只能有一个 [JavaScriptSandbox]，创建昂贵 → 进程级单例，App 生命周期持有。
 * - 引擎能力随设备 WebView 版本浮动 → 必须逐项 [JavaScriptSandbox.isFeatureSupported]：
 *   - [JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN]：evaluate 等 Promise 结算（事件循环的前提）
 *   - [JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS]：JS→宿主 MessagePort 通信
 *   两者任一缺失 → [jsSourcesSupported] = false，**明确禁用 JS 源并记日志**，不静默失败。
 *
 * 线程安全：sandbox 对象 thread-safe；isolate 不可跨线程共享（每引擎自建）。
 */
object JsRuntime {

    private const val TAG = "JsRuntime"

    private var appContext: Context? = null

    private val lock = Any()
    private var sandboxFuture: ListenableFuture<JavaScriptSandbox>? = null

    @Volatile
    private var promiseReturn: Boolean = false

    @Volatile
    private var messagePorts: Boolean = false

    /** 静态能力（不连接进程即可判定）：设备根本不支持 V8 sandbox。 */
    @Volatile
    private var staticallySupported: Boolean = true

    /**
     * JS 源是否可用（V8 sandbox + Promise 事件循环 + MessagePort 全齐）。
     * false 时调用方应禁用 JS 源入口（UI 提示 + 日志），不得静默降级。
     *
     * 注意：首次 sandbox() 连接前 promiseReturn/messagePorts 尚未检测，
     * 此时为 false 仅表示"未检测"，以 [lastDisableReason] 是否为 null 区分。
     */
    val jsSourcesSupported: Boolean
        get() = staticallySupported && promiseReturn && messagePorts

    /**
     * v0.114.0：最近一次判定"JS 源不可用"的原因；null = 未检测出不可用
     * （要么还没检测，要么检测通过）。设置页"JS 引擎状态"行直接读它，
     * 避免用户在 WebView 过旧的设备上看到 JS 源静默没数据。
     */
    @Volatile
    var lastDisableReason: String? = null
        private set

    /** 在 Application.onCreate 里注入（createConnectedInstanceAsync 需要 Context）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        staticallySupported = JavaScriptSandbox.isSupported()
        if (!staticallySupported) {
            lastDisableReason = "设备 WebView 不支持 JavaScriptSandbox"
            LogBus.e(TAG, "设备不支持 JavaScriptSandbox（WebView 过旧/缺失），JS 源已禁用")
        }
    }

    /**
     * 取 sandbox（首次调用触发连接，之后复用）。连接中挂起等待；设备不支持或能力
     * 缺失时返回 null（并置 [jsSourcesSupported] = false）。
     */
    suspend fun sandbox(): JavaScriptSandbox? {
        if (!staticallySupported) return null
        val sandbox = connect() ?: return null
        // 能力逐项检测（isFeatureSupported 随 WebView 版本浮动，必须在实例上确认）。
        // 顺带把全量能力清单记进日志：消息端口等能力是 2026 年才进 WebView 的，
        // 老镜像/老 WebView 缺哪项一眼可见，省得逐个猜。
        val features = listOf(
            JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
            JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
            JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS,
        ).joinToString(", ") { f ->
            val name = f.substringAfterLast('.') // 常量名太长，日志里留短名
            if (sandbox.isFeatureSupported(f)) name else "!" + name
        }
        LogBus.i(TAG, "V8 sandbox 能力清单: [$features]")
        promiseReturn = sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)
        messagePorts = sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)
        if (!promiseReturn) {
            lastDisableReason = "WebView 缺少 Promise 事件循环能力，需升级 WebView"
            LogBus.e(TAG, "V8 sandbox 缺少 JS_FEATURE_PROMISE_RETURN，JS 源已禁用（需升级 WebView）")
            return null
        }
        if (!messagePorts) {
            lastDisableReason = "WebView 缺少消息端口能力，需升级 WebView"
            LogBus.e(TAG, "V8 sandbox 缺少 JS_FEATURE_MESSAGE_PORTS，JS 源已禁用（需升级 WebView）")
            return null
        }
        lastDisableReason = null // 全项通过：清除历史禁用原因
        return sandbox
    }

    /**
     * 测试专用：取已连接的 sandbox（不经过能力门禁）。instrumentation 诊断用——
     * 在缺 MessagePort 的老 WebView 上直跑 isolate 验证 async 事件循环等基础能力。
     * 生产代码一律走 [sandbox]。
     */
    @VisibleForTesting
    internal suspend fun sandboxIgnoringGate(): JavaScriptSandbox? {
        if (!staticallySupported) return null
        return connect()
    }

    /** 连接（惰性单例）。失败时清掉 future 允许重试。 */
    private suspend fun connect(): JavaScriptSandbox? {
        val future = synchronized(lock) {
            sandboxFuture ?: JavaScriptSandbox
                .createConnectedInstanceAsync(requireNotNull(appContext) { "JsRuntime.init 未调用" })
                .also { sandboxFuture = it }
        }
        return try {
            future.awaitJs()
        } catch (e: Throwable) {
            synchronized(lock) { sandboxFuture = null }
            lastDisableReason = "V8 sandbox 进程连接失败（${e.message?.take(40)}），重试或升级 WebView"
            LogBus.e(TAG, "V8 sandbox 连接失败: ${e.message}")
            null
        }
    }

    /** App 退出时关闭（进程级单例，一般不需要显式调）。 */
    fun close() {
        val f = synchronized(lock) { sandboxFuture.also { sandboxFuture = null } }
        runCatching { f?.get()?.close() }
            .onFailure { Log.w(TAG, "sandbox close: ${it.message}") }
    }
}
