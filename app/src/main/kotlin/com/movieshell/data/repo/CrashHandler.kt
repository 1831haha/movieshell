package com.movieshell.data.repo

import android.content.Context
import android.os.Build
import android.util.Log
import com.movieshell.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.25.0：全局崩溃捕获（单入口）。
 *
 * 未捕获异常 → 写 xiaying/crash/crash_yyyyMMdd_HHmmss_SSS.txt
 * （设备信息 + 完整堆栈 + cause 链），只保留最近 20 个，
 * 写完交给原 handler（进程按系统默认行为退出，不吞崩溃）。
 *
 * 必须在 Application.onCreate 里尽早 install，比任何 Activity 都早。
 */
object CrashHandler {
    private const val KEEP = 20
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash(app, t, e) }
            runCatching {
                LogBus.e(
                    "CrashHandler",
                    "崩溃已落盘：${e::class.java.simpleName}: ${e.message}",
                )
            }
            prev?.uncaughtException(t, e)
        }
        LogBus.i("CrashHandler", "全局崩溃捕获已安装")
    }

    /** 最近一次崩溃文件（无则 null）。 */
    fun latest(context: Context): File? = crashFiles(context).maxByOrNull { it.lastModified() }

    /** 全部崩溃文件（按时间升序）。 */
    fun list(context: Context): List<File> = crashFiles(context)

    /** 清空崩溃日志。 */
    fun clear(context: Context) {
        crashFiles(context).forEach { runCatching { it.delete() } }
    }

    /**
     * 打包全部崩溃日志为 zip（供设置页"导出崩溃日志"经 SAF 分享）。
     * 无日志时返回 null。
     */
    fun exportZip(context: Context): File? {
        val files = crashFiles(context)
        if (files.isEmpty()) return null
        val zip = File(
            context.cacheDir,
            "xiaying_crash_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip",
        )
        runCatching {
            java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
                files.forEach { f ->
                    zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }.getOrElse { return null }
        return zip.takeIf { it.exists() && it.length() > 0 }
    }

    private fun crashFiles(context: Context): List<File> =
        runCatching {
            StoragePaths.crashDir(context)
                .listFiles { f -> f.isFile && f.name.startsWith("crash_") }
                .orEmpty().sortedBy { it.name }
        }.getOrDefault(emptyList())

    private fun writeCrash(context: Context, t: Thread, e: Throwable) {
        val dir = StoragePaths.crashDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        sb.appendLine("thread: ${t.name}")
        sb.appendLine("app: ${context.packageName} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine(
            "device: ${Build.MANUFACTURER} ${Build.MODEL} / " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / " +
                Build.SUPPORTED_ABIS.joinToString(","),
        )
        sb.appendLine("exception: ${e::class.java.name}: ${e.message}")
        sb.appendLine("stack:")
        sb.appendLine(Log.getStackTraceString(e))
        var c = e.cause
        var depth = 0
        while (c != null && depth < 5) {
            sb.appendLine("caused by: ${c::class.java.name}: ${c.message}")
            c = c.cause
            depth++
        }
        File(dir, "crash_${ts}.txt").writeText(sb.toString())
        val files = crashFiles(context)
        if (files.size > KEEP) {
            files.take(files.size - KEEP).forEach { runCatching { it.delete() } }
        }
    }
}
