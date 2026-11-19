package com.movieshell.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.movieshell.core.log.LogBus
import com.movieshell.core.util.AtomicFile
import java.util.concurrent.TimeUnit

/**
 * v1.3.25 合并 8：每日静默备份。
 *
 * 每天一次把 BackupManager.exportJson 写到私有目录 backup_daily.json
 * （单文件覆盖，不攒版本）；失败只记日志、走 WorkManager retry，
 * 不弹 Toast、不打扰用户。导出本身有保护：订阅文件损坏或直播配置
 * 读失败时拒绝导出，不会用坏数据覆盖上一次的好备份。
 * 恢复入口在设置页“备份与恢复”里（BackupManager.restoreDailyBackup），
 * 恢复前先写中断快照，与手动导入同一条保护链。
 */
class DailyBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return when (val r = BackupManager.exportJson(applicationContext)) {
            is BackupManager.ExportResult.Ok -> {
                val ok = AtomicFile.writeText(
                    BackupManager.dailyBackupFile(applicationContext),
                    r.json,
                )
                if (ok) {
                    LogBus.i("Backup", "每日静默备份完成：${r.json.length} 字节")
                    Result.success()
                } else {
                    LogBus.w("Backup", "每日静默备份写盘失败")
                    Result.retry()
                }
            }
            is BackupManager.ExportResult.Failed -> {
                LogBus.w("Backup", "每日静默备份导出失败：${r.reason}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val NAME = "daily-backup"

        /** App 启动时调用排期（MainActivity.onCreate，与追剧检测同款位置）。 */
        fun reschedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<DailyBackupWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
