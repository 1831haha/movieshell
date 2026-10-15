package com.movieshell.data.source

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var libsPathAdded = false

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun isInitialized(): Boolean = appContext != null

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
    private fun pyPkgVersion(py: Python, name: String): String = runCatching {
        py.getModule("importlib.metadata")?.callAttr("version", name)?.toString()
            ?: "unknown"
    }.getOrDefault("unknown")

    private fun envSelfCheck(py: Python, ctx: Context) {
        if (selfChecked) return
        selfChecked = true
        runCatching {
            val dir = com.movieshell.data.repo.StoragePaths.pyLibsDir(ctx)
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
            val dir = com.movieshell.data.repo.StoragePaths.pyLibsDir(ctx)
            py.getModule("sys")?.get("path")?.callAttr("insert", 0, dir.absolutePath)
            Log.i(TAG, "pylibs 已加入 sys.path：${dir.absolutePath}")
        }.onFailure { Log.w(TAG, "pylibs 加入 sys.path 失败", it) }
        libsPathAdded = true
    }

    override suspend fun invoke(rulePath: String, method: String, argsJson: String): String =
        withContext(Dispatchers.IO) {
            try {
                val mod = python().getModule("pyspider_bridge")
                mod.callAttr("call", rulePath, method, argsJson).toString()
            } catch (e: Exception) {
                Log.w(TAG, "invoke 失败: $method", e)
                "{\"ok\":false,\"error\":\"" + (e.message ?: e.toString()).replace("\"", "'") + "\"}"
            }
        }
}
