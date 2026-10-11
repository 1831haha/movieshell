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

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun isInitialized(): Boolean = appContext != null

    private fun python(): Python {
        val ctx = appContext
            ?: throw IllegalStateException("ChaquopyEngine.init() 尚未调用")
        if (!Python.isStarted()) {
            Log.i(TAG, "首次启动 Python 解释器（约 1-2 秒）…")
            Python.start(AndroidPlatform(ctx))
            Log.i(TAG, "Python 解释器已启动")
        }
        return Python.getInstance()
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
