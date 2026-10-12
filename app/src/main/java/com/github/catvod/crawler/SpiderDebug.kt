package com.github.catvod.crawler

import com.movieshell.data.repo.LogBus

/**
 * v0.8.0：jar 里 spider 调用的宿主静态日志（dex 实测为 invoke-static）。
 */
object SpiderDebug {
    @JvmStatic
    fun log(msg: String) {
        LogBus.i("SpiderDebug", msg)
    }

    @JvmStatic
    fun log(e: Throwable) {
        LogBus.e("SpiderDebug", e.message ?: e.toString())
    }
}
