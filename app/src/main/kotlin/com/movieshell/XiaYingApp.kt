package com.movieshell

import android.app.Application
import com.movieshell.data.novel.NovelMigration
import com.movieshell.data.novel.NovelUpdateWorker
import com.movieshell.data.repo.CrashHandler
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.ServicePluginManager
import com.movieshell.data.source.JsRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        // v0.113.0：V8 sandbox 上下文注入（进程级单例，首次用 JS 源时才真正连接）
        JsRuntime.init(this)
        // v0.85.0：小说自动追更（每天一次，KEEP 防重复入队）
        NovelUpdateWorker.schedule(this)
        // v0.95.0：小说旧数据一次性备份（.bak），后台跑不阻塞启动
        CoroutineScope(Dispatchers.IO).launch { NovelMigration.runOnce(this@XiaYingApp) }
        // v0.132.0：拉起设了自启动的插件服务（失败只记日志，不阻塞启动）
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ServicePluginManager.startAutoStartServices(this@XiaYingApp) }
        }
    }
}
