package com.movieshell.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.movieshell.MainActivity
import com.movieshell.R

/**
 * 通知单入口（v0.26.0）。
 *
 * - 追剧更新：点通知直达详情页（带 subkey 恢复站点上下文）。
 * - 下载进度：前台服务式常驻通知（Worker 用 setForeground 接管）。
 */
object Notify {

    const val CH_FOLLOW = "follow_update"
    const val CH_DOWNLOAD = "download"
    // v0.85.0：小说自动追更
    const val CH_NOVEL = "novel_update"
    // v0.90.0：站源批量检测
    const val CH_PROBE = "source_probe"

    const val EXTRA_ROUTE = "xiaying.route"
    const val EXTRA_SUBKEY = "xiaying.subkey"
    const val ACTION_ROUTE = "xiaying.ROUTE"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_FOLLOW) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_FOLLOW, "追剧更新", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (nm.getNotificationChannel(CH_DOWNLOAD) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_DOWNLOAD, "下载", NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (nm.getNotificationChannel(CH_NOVEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_NOVEL, "小说追更", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (nm.getNotificationChannel(CH_PROBE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_PROBE, "源检测", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /** 打开 App 并跳到指定路由的 PendingIntent。 */
    fun routeIntent(context: Context, route: String, subKey: String = "", id: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "xiaying.ROUTE"
            putExtra(EXTRA_ROUTE, route)
            putExtra(EXTRA_SUBKEY, subKey)
            data = Uri.parse("xiaying://route/$id/${route.hashCode()}")
        }
        return PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 追剧更新通知。 */
    fun followUpdate(context: Context, title: String, text: String, vodId: String, subKey: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = routeIntent(context, "detail/${Uri.encode(vodId)}", subKey, vodId.hashCode())
        val n = NotificationCompat.Builder(context, CH_FOLLOW)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(vodId.hashCode(), n) }
    }

    /** 下载进度通知（供 Worker 前台调用）。 */
    fun downloadProgress(context: Context, title: String, progress: Int): android.app.Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
    }

    /** 下载完成通知（点通知进下载管理页）。 */
    fun downloadDone(context: Context, title: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = routeIntent(context, "downloads", id = title.hashCode())
        val n = NotificationCompat.Builder(context, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("下载完成")
            .setContentText(title)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(title.hashCode(), n) }
    }

    /** v0.85.0：小说自动追更通知（点通知进书架）。 */
    fun novelUpdate(context: Context, bookCount: Int, chapterCount: Int) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = routeIntent(context, "bookshelf", id = 9002)
        val n = NotificationCompat.Builder(context, CH_NOVEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("小说追更")
            .setContentText("$bookCount 本书共新增 $chapterCount 章")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(9002, n) }
    }

    /** v0.90.0：批量检测完成通知（点通知回站源页检测 tab）。 */
    fun probeDone(context: Context, total: Int, ok: Int, slow: Int, fail: Int) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = routeIntent(context, "sources?tab=2", id = 9003)
        val n = NotificationCompat.Builder(context, CH_PROBE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("源检测完成")
            .setContentText("共 $total 个源：正常 $ok，缓慢 $slow，失败 $fail")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(9003, n) }
    }

    /** 进入追剧/下载页时调用一次：API 33+ 申请通知权限。 */
    @Composable
    fun RequestNotificationPermissionOnce() {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
