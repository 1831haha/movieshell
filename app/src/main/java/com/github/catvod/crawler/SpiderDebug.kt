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
        // v0.247.0（F31）：原来只记 message，无堆栈定位不了；签名不动
        //（jar 侧 dex 直调），只在消息后附堆栈。
        LogBus.e("SpiderDebug", e.message ?: e.toString(), e)
    }
}
