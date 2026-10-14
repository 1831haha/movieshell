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
        return py
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
