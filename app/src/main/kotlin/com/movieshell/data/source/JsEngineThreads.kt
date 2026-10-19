package com.movieshell.data.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

/**
 * v0.111.0：JS 引擎线程池（一引擎一线程）。
 *
 * 背景：QuickJS 的 JSContext 只能绑一个线程，多线程并发进 JNI 会 SIGSEGV
 * （v0.99.1 单线程化的原因）。本方案不违反这条——每个引擎的 QuickJS 上下文
 * 只在自己的独占线程里创建/使用/关闭，永不出线程、不跨引擎共享；只是把
 * "全 App 共用一条 JS 线程"的单点故障拆成了每个引擎一条。
 *
 * 并发上限：[MAX_ENGINES] 个（信号量控制），第 9 个引擎的 acquire 会阻塞等待空位。
 * 线程安全：计数器用 AtomicInteger，许可归还与获取经信号量，不许裸 var。
 */
object JsEngineThreads {

    /** 全 App 同时存活的 JS 引擎线程上限，超了排队。 */
    const val MAX_ENGINES = 8

    private val semaphore = Semaphore(MAX_ENGINES)
    private val counter = AtomicInteger(0)

    /**
     * 看门狗作用域：引擎线程的超时 grace 检查用（evaluateActive 超时后
     * 仍不返回即判定死锁）。SupervisorJob + Default，不随引擎释放而取消。
     */
    val watchdogScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 获取一条独占 JS 线程。阻塞直到有空位（可中断），返回 daemon 单线程 dispatcher；
     * 线程名 "js-engine-<tag>-<n>"，随进程结束消亡（daemon）。
     */
    fun acquireDispatcher(tag: String): ExecutorCoroutineDispatcher {
        semaphore.acquire()
        return Executors.newSingleThreadExecutor { r ->
            Thread(r, "js-engine-$tag-${counter.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * 归还 dispatcher：shutdownNow 中断空闲/排队任务并释放许可。
     *
     * 已知泄漏：卡死在 JNI（QuickJS 死循环）的线程 shutdownNow 也杀不掉，
     * daemon 线程只能随进程结束消亡——这是无 JNI 中断机制下的权衡，
     * 8 线程上限内可接受，此处不重试、不等待、不抛异常。
     */
    fun releaseDispatcher(d: ExecutorCoroutineDispatcher) {
        runCatching { (d.executor as? ExecutorService)?.shutdownNow() }
        semaphore.release()
    }

    /**
     * 在 dispatcher 线程上执行收尾 task（如 QuickJS close），然后归还许可。
     * 若线程已死（task 永不执行），3s 后直接归还许可——归还只发生一次，
     * 不会因"task 恰好跑完"和"后备触发"竞态而 double-release 信号量。
     */
    fun retireDispatcher(d: ExecutorCoroutineDispatcher, task: () -> Unit) {
        val done = AtomicBoolean(false)
        val finish: () -> Unit = {
            if (done.compareAndSet(false, true)) releaseDispatcher(d)
        }
        try {
            d.dispatch(EmptyCoroutineContext, Runnable {
                runCatching { task() }
                finish()
            })
            watchdogScope.launch {
                delay(RETIRE_FALLBACK_MS)
                if (!done.get()) finish()
            }
        } catch (e: RejectedExecutionException) {
            finish()
        }
    }

    /** retireDispatcher 的后备等待：线程已死时 task 永不执行，超时后直接归还许可。 */
    private const val RETIRE_FALLBACK_MS = 3_000L
}
