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
