package com.movieshell.player

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.movieshell.data.repo.LogBus
import java.io.File

/**
 * v0.294.29：磁力引擎独立进程（android:process=":magnet"）。
 * libtorrent 原生崩溃只会杀掉这个进程，主程序不受影响；主进程经回环 HTTP（/ctl/ 接口）控制。
 */
class MagnetService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // v0.294.38：前台服务 + 通知，切到后台 / 画中画时系统不回收磁力进程
        runCatching {
            com.movieshell.data.download.Notify.ensureChannels(this)
            val n = androidx.core.app.NotificationCompat.Builder(this, com.movieshell.data.download.Notify.CH_DOWNLOAD)
                .setSmallIcon(com.movieshell.R.drawable.ic_stat)
                .setContentTitle("侠影磁力引擎运行中")
                .setContentText("边下边播中，离开播放后自动停止")
                .setOngoing(true).setSilent(true)
                .setContentIntent(com.movieshell.data.download.Notify.routeIntent(this, "home", id = 9101))
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(9101, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else startForeground(9101, n)
        }.onFailure { LogBus.w("XiaYingMagnet", "磁力前台服务启动失败：${it.message}") }
        Thread({
            try {
                XiaYingMagnetEngine.ensureHttp(applicationContext)
                val port = XiaYingMagnetEngine.port()
                val f = MagnetRemote.portFile(applicationContext)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(port.toString()); tmp.renameTo(f)
            } catch (t: Throwable) {
                LogBus.e("XiaYingMagnet", "磁力进程启动失败：${t.message}")
                runCatching {
                    MagnetRemote.portFile(applicationContext).writeText("fail:" + (t.message ?: t::class.java.simpleName))
                }
            }
        }, "magnet-boot").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 主界面划掉：磁力进程一起退出，不在后台下载
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
