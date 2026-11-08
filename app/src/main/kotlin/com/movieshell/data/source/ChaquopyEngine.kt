package com.movieshell.data.source

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.movieshell.core.log.LogBus
import com.movieshell.core.util.StoragePaths
import com.movieshell.ui.components.AppToast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Chaquopy 内置 Python 引擎。
 *
 * - init(context)：Application 级调用一次（MainActivity.onCreate），只存 applicationContext。
 * - Python 解释器懒启动：第一次真正调用 PY 规则时才 Python.start()，
 *   绝不在 Application.onCreate 里启动，避免拖慢冷启动。
 * - 所有调用切到 Dispatchers.IO，桥本身是同步的 Java 接口。
 */
object ChaquopyEngine : PyEngine {

    private const val TAG = "ChaquopyEngine"

    /**
     * v0.187.0 H03：PY 桥调用超时，与 V8DrpyEngine 的 JS_TIMEOUT_MS 同档 15 秒。
     * 常量写法照抄 V8DrpyEngine.kt 的 private const val JS_TIMEOUT_MS = 15_000L
     * （该常量是 private，跨文件无法直接引用，故在此复写同值并注明来源）。
     * v0.294.46：PY 超时放宽到 25 秒，与首页 25 秒熔断对齐——首页推荐与分类共用一次
     * homeContent 调用，15 秒一到两者一起死、首页整页红圈连分类都进不去；
     * 15–25 秒这个区间的慢源（如手机网络下的福利天堂、xl01）原来是被桥先掐死的，
     * 不是源本身没数据。代价不变：后台 Python 仍停不掉，见下方注释。
     */
    private const val PY_TIMEOUT_MS = 25_000L

    /** v0.203.0：yt-dlp 通用解析超时（首次 import 全量提取器约 1–2 秒 + 取页，15 秒偏紧）。 */
    private const val YT_TIMEOUT_MS = 30_000L

    /**
     * v0.187.0 H03：桥调用专用作用域，与调用方作用域脱钩。
     * 写法照抄 PySourceManager.kt 的 readScope（CoroutineScope + SupervisorJob + IO）。
     * 脱钩是必须的：桥调用是同步阻塞接口，不响应协程取消，直接在调用上套
     * withTimeout 等不到超时；只有把调用放进独立 scope 的 async、调用方只带超时等 await，
     * 调用方才能在 25 秒时真正放行。代价见 callBridge 注释：后台 Python 停不掉。
     */
    /**
     * v0.294.5：PY 调用并发封顶——原来挂 Dispatchers.IO（最多 64 线程），慢源/死循环的
     * 超时调用在后台停不掉、一次次叠加成线程堆积。改为固定 4 线程的专用池：
     * 同时最多 4 个 Python 调用在跑，多出的排队；排队中超时/被取消的调用直接作废、
     * 不再启动（见 callBridge 的 deferred.cancel），超时调用不会无限叠加。
     */
    private const val PY_MAX_CONCURRENCY = 4
    private val pyDispatcher = java.util.concurrent.Executors.newFixedThreadPool(PY_MAX_CONCURRENCY) { r ->
        Thread(r, "py-bridge").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val pyRunning = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * v0.294.48 PY 池防毒化：只检测、不假装能杀线程。
     * - Chaquopy 桥是同步阻塞接口，Thread.interrupt / deferred.cancel 都停不掉
     *   正在跑的 Python，所谓“杀掉卡死调用”在进程内做不到，硬做只会把
     *   解释器状态搞坏。唯一可靠的恢复是：卡死的调用自己结束（池自动恢复），
     *   或永远不结束时用户重启 App（进程重建）。
     * - 所以本版只做三件真实的事：①给每个在跑调用记开始时间，算出池子
     *   是否已毒化；②毒化后新调用不再排队等 25 秒，直接失败返回，让界面
     *   立刻知道不是源的问题；③毒化首次出现时给一次可见提示，恢复后清掉
     *   标记，下一次毒化再提示一次，不刷屏。
     * - 毒化定义要严：4 个槽全满、且每条都已跑超 60 秒。60 秒 = 25 秒调用
     *   超时 + 35 秒宽限，调用方早已不要结果了，还占着槽才是毒；单条慢、
     *   或槽没满，都不算毒化，不误伤正常慢源。
     */
    private const val PY_STUCK_AFTER_MS = 60_000L
    private data class RunningPyCall(val rulePath: String, val method: String, val startedAtMs: Long)
    private val runningCalls = ConcurrentHashMap<Long, RunningPyCall>()
    private val callSeq = AtomicLong(0)
    @Volatile
    private var poolStuckNotified = false

    /** 池子是否已毒化：4 槽全满且每条在跑调用都超 60 秒。未毒化时返回 false，不误判。 */
    fun poolPoisoned(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val calls = runningCalls.values
        return calls.size >= PY_MAX_CONCURRENCY &&
            calls.all { now - it.startedAtMs > PY_STUCK_AFTER_MS }
    }

    /** 毒化时返回给用户看的一句话，未毒化返回 null（调用方按 null 走正常失败路）。 */
    fun poolStuckMessage(): String? {
        if (!poolPoisoned()) return null
        val oldest = runningCalls.values.minByOrNull { it.startedAtMs } ?: return null
        val ageSec = (SystemClock.elapsedRealtime() - oldest.startedAtMs) / 1000
        return "PY 调用池已卡死：4 条调用均已运行超 60 秒未回（最早：${oldest.method}，已跑 ${ageSec} 秒），后续 PY 调用不再排队等待，重启 App 可恢复"
    }

    /**
     * v0.294.48 补：毒化快拒的 10 秒宽限。毒化时新调用不是立刻拒：先挂起
     * 等最多 10 秒，看有没有在跑调用恰好结束空出槽——空出来就正常提交，
     * 照常走 25 秒超时；10 秒还卡死才直接失败。宽限里只 delay，不占池
     * 线程；delay 可被取消，调用方取消时直接透出，不吞取消语义。
     * 10 秒不是给慢网页留的，慢网页本身仍受 25 秒上限；它只保“马上就
     * 回来的那条线程”不被一刀切掉。
     */
    private suspend fun waitForPoolRecovery(): Boolean {
        if (!poolPoisoned()) return true
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (poolPoisoned()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(500L)
        }
        return true
    }

    /** 毒化首次出现时记日志 + 弹一次提示；未毒化时清标记，供下一次毒化再提示。 */
    private fun notifyPoolStuckIfNeeded() {
        val msg = poolStuckMessage()
        if (msg == null) {
            poolStuckNotified = false
            return
        }
        if (poolStuckNotified) return
        poolStuckNotified = true
        Log.w(TAG, msg)
        LogBus.w(TAG, msg)
        val ctx = appContext
        // 只在应用内提示层已挂载时弹：后台线程调系统 Toast 可能因无 Looper 崩，
        // 未挂载时只记日志，不丢提示也不冒险。
        if (ctx != null && AppToast.mounted.get()) {
            AppToast.show(ctx, msg, long = true)
        }
    }

    private val pyCallScope = CoroutineScope(SupervisorJob() + pyDispatcher)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var libsPathAdded = false

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /**
     * v0.27.2：加锁。之前未加锁，并发协程可能同时通过 isStarted 检查、
     * 各调一次 Python.start()，真机报过 "Python already started"。
     */
    @Synchronized
    private fun python(): Python {
        val ctx = appContext
            ?: throw IllegalStateException("ChaquopyEngine.init() 尚未调用")
        if (!Python.isStarted()) {
            Log.i(TAG, "首次启动 Python 解释器（约 1-2 秒）…")
            Python.start(AndroidPlatform(ctx))
            Log.i(TAG, "Python 解释器已启动")
        }
        val py = Python.getInstance()
        ensureLibsPath(py, ctx)
        envSelfCheck(py, ctx)
        return py
    }

    /**
     * v0.27.4：PY 环境自检（每次拿解释器时打一次，INFO 级）。
     * 真机实锤：依赖包显示已安装，但详情仍报 XMLSyntaxError（USC4），
     * 说明"brotli 缺口"的判断可能不准——先拿数据，不猜。
     * 自检内容：brotli / charset_normalizer 能否 import、pylibs 是否在
     * sys.path、pylibs 下实际文件列表（防 zip 解压出双层前缀导致 import 失败）。
     */
    private var selfChecked = false

    /**
     * v0.27.7：取 PY 包版本号（拿不到就 "unknown"，不抛）。
     * importlib.metadata 在 Chaquopy 下可能取不到子模块，包一层 runCatching。
     */
    /**
     * v0.294.46：PY 解释器预热（后台调用，不抛）。Application 启动时在 IO 线程跑一次，
     * 把 Python.start() 约 1–2 秒的启动费从首个 PY 源的首屏里挪到启动后台；
     * 之后第一次 homeContent 不用再等解释器启动，只剩站点网络时间。
     * 已启动时 python() 直接返回，不重复启动。
     */
    fun warmup() {
        runCatching { python() }
            .onSuccess { Log.i(TAG, "PY 解释器预热完成") }
            .onFailure { Log.w(TAG, "PY 解释器预热失败（首个 PY 源调用时再启动）", it) }
    }

    private fun pyPkgVersion(py: Python, name: String): String = runCatching {
        py.getModule("importlib.metadata")?.callAttr("version", name)?.toString()
            ?: "unknown"
    }.getOrDefault("unknown")

    private fun envSelfCheck(py: Python, ctx: Context) {
        if (selfChecked) return
        selfChecked = true
        runCatching {
            val dir = StoragePaths.pyLibsDir(ctx)
            val sysPath = py.getModule("sys")?.get("path")?.toString().orEmpty()
            val inPath = sysPath.contains(dir.absolutePath)
            val brotliOk = runCatching { py.getModule("brotli"); true }.getOrDefault(false)
            val cnOk = runCatching { py.getModule("charset_normalizer"); true }.getOrDefault(false)
            // v0.27.7：黄果详情 USC4 在 patch 生效后依然出现，桌面复现不了，
            // 疑似手机 lxml 构建问题——把解析链版本号打出来，只加日志不改逻辑
            val lxmlVer = pyPkgVersion(py, "lxml")
            val pqVer = pyPkgVersion(py, "pyquery")
            val cnVer = pyPkgVersion(py, "charset-normalizer")
            val files = dir.walkTopDown().maxDepth(2).filter { it.isFile }
                .map { it.relativeTo(dir).path }.sorted().toList()
            Log.i(
                TAG,
                "PY 环境自检：brotli可import=$brotliOk " +
                    "charset_normalizer可import=$cnOk " +
                    "pylibs在sys.path=$inPath " +
                    "pylibs文件数=${files.size} " +
                    "lxml=$lxmlVer pyquery=$pqVer charset-normalizer=$cnVer " +
                    files.take(12).joinToString(","),
            )
        }.onFailure { Log.w(TAG, "PY 环境自检失败", it) }
    }

    /**
     * v0.27.2：把插件装的 PY 依赖包目录加入 sys.path（进程级一次即可；
     * 目录项先占位，依赖包后装也能被 import 到）。
     */
    private fun ensureLibsPath(py: Python, ctx: Context) {
        if (libsPathAdded) return
        runCatching {
            val dir = StoragePaths.pyLibsDir(ctx)
            py.getModule("sys")?.get("path")?.callAttr("insert", 0, dir.absolutePath)
            Log.i(TAG, "pylibs 已加入 sys.path：${dir.absolutePath}")
        }.onSuccess {
            // v0.188.0 M14：此前失败也照常置位，之后永不再重试，依赖缺失
            // 会被误当成“已加过”。只有真正加成功才置位，失败下次再试。
            libsPathAdded = true
        }.onFailure { Log.w(TAG, "pylibs 加入 sys.path 失败", it) }
    }

    /**
     * v0.187.0 H03：桥调用统一入口，invoke 与 bookInvoke 共用，只在此一层加超时，
     * PyApi 层不再另加，避免双重超时。
     * 固有限制，如实写明：超时只放行调用方，不中断正在执行的 Python。
     * Chaquopy 桥是同步阻塞的 Java 接口，不响应协程取消，超时后也不 cancel
     * 后台任务（cancel 同样停不掉它）；规则死循环时后台 Python 仍会跑到结束，
     * 只占一条 IO 线程，其结果因调用方已超时而丢弃，不会回写。
     * 超时后记规则路径与调用名，不静默：LogBus 与失败 JSON 双通道。
     */
    private suspend fun callBridge(
        rulePath: String,
        method: String,
        argsJson: String,
        bridgeMethod: String,
    ): String {
        // v0.294.48：池已毒化时先等 10 秒宽限（见 waitForPoolRecovery），宽限
        // 内空出槽就正常提交；宽限后还卡死才直接失败，不提交排队——排队只
        // 会再等 25 秒超时，既浪费用户等待，又让“所有 PY 源全挂”看起来像
        // 源的问题。卡死的调用自己结束时池自动恢复（finally 里清标记），
        // 不需要重启也能回来。
        if (poolPoisoned() && !waitForPoolRecovery()) {
            notifyPoolStuckIfNeeded()
            val stuck = poolStuckMessage() ?: "PY 调用池已卡死"
            Log.w(TAG, "PY 调用被拒（池已卡死，不排队）: 规则=$rulePath 方法=$method")
            return "{\"ok\":false,\"error\":\"$stuck\"}"
        }
        val callId = callSeq.incrementAndGet()
        val deferred = pyCallScope.async {
            // 只在真正开跑时登记：排队中被取消的调用根本不进本块，不会误记为在跑。
            runningCalls[callId] = RunningPyCall(rulePath, method, SystemClock.elapsedRealtime())
            pyRunning.incrementAndGet()
            try {
                val mod = python().getModule("pyspider_bridge")
                mod.callAttr(bridgeMethod, rulePath, method, argsJson).toString()
            } finally {
                runningCalls.remove(callId)
                pyRunning.decrementAndGet()
                if (runningCalls.size < PY_MAX_CONCURRENCY) poolStuckNotified = false
            }
        }
        return try {
            withTimeoutOrNull(PY_TIMEOUT_MS) { deferred.await() } ?: run {
                // v0.294.5：还在排队没开跑的直接作废；已在跑的停不掉，但占的是封顶池里的线程
                deferred.cancel()
                // v0.294.48：本次超时若正好撞上池毒化，交毒化原因而不是普通超时，
                // 调用方（适配器 lastError、日志）才能分清“源慢”与“池已卡死”。
                val stuck = poolStuckMessage()
                if (stuck != null) {
                    notifyPoolStuckIfNeeded()
                    "{\"ok\":false,\"error\":\"$stuck\"}"
                } else {
                    Log.w(TAG, "PY 调用超时（25 秒）: 规则=$rulePath 方法=$method 在跑=${pyRunning.get()}")
                    LogBus.w(
                        TAG,
                        "PY 调用超时（25 秒）: 规则=$rulePath 方法=$method，" +
                            "后台 Python 无法中断仍会跑到结束，结果丢弃",
                    )
                    "{\"ok\":false,\"error\":\"PY 调用超时（25 秒）:$method\"}"
                }
            }
        } catch (e: CancellationException) {
            // v0.294.5：调用方取消时把排队中的调用一并作废，不白占 PY 池
            deferred.cancel()
            // 调用方取消必须透出，不能被下方的 Exception 分支吞成失败 JSON，
            // 否则 PySourceManager“旧调用方收到 CancellationException”的契约落空。
            // 注意：这只是调用方等待的取消，桥上 Python 本身仍停不掉（见上文固有限制）。
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "PY 桥调用失败: $method", e)
            "{\"ok\":false,\"error\":\"" + (e.message ?: e.toString()).replace("\"", "'") + "\"}"
        }
    }

    override suspend fun invoke(rulePath: String, method: String, argsJson: String): String =
        callBridge(rulePath, method, argsJson, "call")

    /** v0.64.0 书源入口：走 pyspider_bridge.book_call。 */
    override suspend fun bookInvoke(rulePath: String, method: String, argsJson: String): String =
        callBridge(rulePath, method, argsJson, "book_call")

    /**
     * v0.291.0：proxy:// 封面取图。部分 PY 规则把封面写成 proxy://do=py&url=..，
     * 只有规则自己的 localProxy 知道取图要带什么 Referer；桥一律走 JSON 字符串，
     * 图片字节经 pyspider_bridge.proxy_image 转 base64 回传，这里再解回字节。
     * 规则取图失败时按约定返回 1x1 透明 png（约 70 字节），按过小判失败返回 null，
     * 让图片加载回落首字占位，不把透明小图当成封面。
     */
    data class ProxyImage(val bytes: ByteArray, val contentType: String?)

    suspend fun fetchProxyImage(rulePath: String, imageUrl: String): ProxyImage? {
        val escaped = imageUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        val text = callBridge(rulePath, "localProxy", "{\"url\":\"$escaped\"}", "proxy_image")
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            if ((root["ok"] as? JsonPrimitive)?.contentOrNull != "true") return null
            val data = (root["data"] as? JsonObject) ?: return null
            val encoded = (data["base64"] as? JsonPrimitive)?.contentOrNull ?: return null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (bytes.size < 200) return null
            ProxyImage(
                bytes,
                (data["contentType"] as? JsonPrimitive)?.contentOrNull,
            )
        }.getOrNull()
    }

    /** v0.64.0：清掉指定规则文件的桥缓存。 */
    override suspend fun resetRuleCache(rulePath: String): Unit = withContext(Dispatchers.IO) {
        runCatching {
            python().getModule("pyspider_bridge").callAttr("reset", rulePath)
        }.onFailure { Log.w(TAG, "resetRuleCache 失败", it) }
    }

    /**
     * v0.203.0：yt-dlp 通用解析。调插件 yt-dlp-generic 提供的 yt_generic.resolve。
     * 插件未装时 getModule 抛异常，包成空串返回，不触发 ParseApi 的熔断；
     * 超时只放行调用方（桥是同步阻塞接口，后台 Python 停不掉，同 callBridge）。
     */
    suspend fun resolveYtGeneric(url: String): String = withContext(Dispatchers.IO) {
        // v0.294.48：同池共用，同款防毒化——原来这里既不登记在跑调用（毒化
        // 检测会漏算它），超时也不 cancel（排队中的调用白占位置）。现在与
        // callBridge 对齐：毒化直接回空、开跑登记、超时作废排队项。
        if (poolPoisoned() && !waitForPoolRecovery()) {
            notifyPoolStuckIfNeeded()
            Log.w(TAG, "yt-dlp 解析被拒（PY 池已卡死，不排队）")
            return@withContext ""
        }
        val callId = callSeq.incrementAndGet()
        val deferred = pyCallScope.async {
            runningCalls[callId] =
                RunningPyCall("yt-dlp-generic", "resolve", SystemClock.elapsedRealtime())
            pyRunning.incrementAndGet()
            try {
                runCatching {
                    python().getModule("yt_generic")?.callAttr("resolve", url)?.toString().orEmpty()
                }.getOrDefault("")
            } finally {
                runningCalls.remove(callId)
                pyRunning.decrementAndGet()
                if (runningCalls.size < PY_MAX_CONCURRENCY) poolStuckNotified = false
            }
        }
        val result = withTimeoutOrNull(YT_TIMEOUT_MS) { deferred.await() }
        if (result == null) {
            deferred.cancel()
            notifyPoolStuckIfNeeded()
        }
        result.orEmpty()
    }
}
