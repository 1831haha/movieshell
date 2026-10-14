package com.movieshell

import android.app.Application
import com.movieshell.data.repo.CrashHandler
import com.movieshell.data.repo.LogBus

/**
 * v0.25.0：Application 入口。
 * 崩溃捕获必须在 onCreate 里最先安装，比任何 Activity/Service 都早，
 * 否则启动期的崩溃抓不到。
 */
class XiaYingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        LogBus.readLastCrash(this)
    }
}
