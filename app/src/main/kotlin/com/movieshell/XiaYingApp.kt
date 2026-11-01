package com.movieshell

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.movieshell.data.maintain.CacheAutoClean
import com.movieshell.data.novel.LegadoJsEngine
import com.movieshell.data.novel.NovelMigration
import com.movieshell.data.novel.NovelUpdateWorker
import com.movieshell.data.repo.CrashHandler
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.ServicePluginManager
import com.movieshell.data.repo.SpiderEngine
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubSiteEngines
import com.movieshell.data.source.ChaquopyEngine
import com.movieshell.data.source.JsRuntime
import com.movieshell.data.source.ProxyImageFetcher
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
        // v0.294.29：:magnet 子进程只跑磁力引擎，不做主进程那套初始化
        if (com.movieshell.data.repo.ProcessExitLog.isMagnetProcess(this)) return
        LogBus.readLastCrash(this)
        // v0.294.29：上次进程（含 :magnet）非正常退出原因写进运行日志（原生崩溃 Java 抓不到）
        com.movieshell.data.repo.ProcessExitLog.logAsync(this)
        // v0.294.28：启动只清磁力缓存目录，绝不加载 libtorrent（避免 JNI 启动闪退）
        runCatching { com.movieshell.player.MagnetCache.pruneAsync(this) }
        // v0.291.0：proxy:// 封面取图器——只接管 proxy:// 地址，其余走 Coil 默认，
        // 须在任何图片加载前注册，故放在 Application.onCreate。
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(ProxyImageFetcher.Factory()) }
                .build(),
        )
        // v0.113.0：V8 sandbox 上下文注入（进程级单例，首次用 JS 源时才真正连接）
        JsRuntime.init(this)
        // B3：引擎与相关 Store 的初始化挪到 Application——原来只在
        // MainActivity.onCreate 里调，WorkManager 单独拉起进程时
        // Activity 根本没跑，追剧检测调 SiteRepository.detail 会因
        // 引擎未 init 抛错，又被 logged() 吞成空详情当“没更新”。
        // 三个 init 都是幂等的（appContext 为空时才赋值），主进程
        // 里 MainActivity 再调一次无害。
        SubSiteEngines.init(this)
        SpiderEngine.init(this)
        PhpSourceManager.init(this)
        // v0.294.46：PY 解释器后台预热——原来第一次点 PY 源时才 Python.start()，
        // 1–2 秒启动费全算在首屏里；启动时后台预热，首屏只剩站点网络时间。
        // 不阻塞启动：失败只记日志，首个 PY 源调用时再按老路启动。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                ChaquopyEngine.init(this@XiaYingApp)
                ChaquopyEngine.warmup()
            }
        }
        // v0.294.46：V8 sandbox 同理后台预热——原来第一次用 JS 源时才连 sandbox，
        // 连接费算在首屏里；启动时后台先连好，JS 源首屏同样只剩站点网络时间。
        // sandbox() 自带能力门禁与失败日志，不阻塞启动，失败不影响后续正常调用。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { JsRuntime.sandbox() }
        }
        // v0.200.0：小说 JS 源变量落盘读回（重定向域名记忆跨重启生效）
        LegadoJsEngine.SourceVariableStore.init(filesDir)
        // v0.85.0：小说自动追更（每天一次，KEEP 防重复入队）
        NovelUpdateWorker.schedule(this)
        // v0.162.0：图片缓存超 1GB 时每日空闲清理一次（只清图片，不碰视频 SimpleCache）
        CacheAutoClean.schedule(this)
        // v0.95.0：小说旧数据一次性备份（.bak），后台跑不阻塞启动
        CoroutineScope(Dispatchers.IO).launch { NovelMigration.runOnce(this@XiaYingApp) }
        // v0.132.0：拉起设了自启动的插件服务（失败只记日志，不阻塞启动）
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ServicePluginManager.startAutoStartServices(this@XiaYingApp) }
        }
        // v0.192.0：给已装插件补 .nomedia（幂等，后台跑不阻塞启动）：
        // 光靠安装时写，2026-10-23 之前装好的 magnet-stream 这类旧插件
        // 还是漏网；下次启动补上后，配合 1.0.6 更新删掉旧 .ts 文件，
        // 重启触发媒体扫描后相册里的假视频才会跟着消失。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { StoragePaths.ensureNomediaForPluginDirs(this@XiaYingApp) }
        }
    }
}
