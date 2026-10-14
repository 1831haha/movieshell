package com.movieshell.data.repo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * v0.7.2：用户可管理的文件统一走公开目录。
 *
 * 用户拍板路径：/storage/emulated/0/xiaying/
 * - js/      ← 原 filesDir/drpy/（drpy 规则）
 * - py/      ← 原 filesDir/py/
 * - php/     ← 原 filesDir/php/
 * - web/     ← 原 filesDir/web/（网页源）
 * - jar/      预留（v0.8.0 spider jar 缓存）
 * - backup/   备份文件默认导出位置
 * - log/      运行日志导出位置
 *
 * app-state JSON（api_sources.json、sub_sites.json、搜索历史、收藏、设置、主题）
 * 继续留在私有 filesDir，不在此管理。
 *
 * 权限：API>=30 需要 MANAGE_EXTERNAL_STORAGE（AndroidManifest 已声明）；
 * API<30 用 WRITE/READ_EXTERNAL_STORAGE。无权限时各 dir() 回退到
 * filesDir/xiaying/<子目录>，App 照常用，不崩。
 *
 * 升级迁移：migrateFromPrivate() 把旧私有目录搬到公开目录，幂等，
 * 必须在各 Manager init 之前调（见 MainActivity.onCreate）。
 */
object StoragePaths {
    private const val TAG = "StoragePaths"

    /** 用户拍板的公开根目录名 */
    const val ROOT_NAME = "xiaying"

    /** 用户拍板的完整路径（标准设备上与 publicRoot() 一致） */
    const val PUBLIC_ROOT_PATH = "/storage/emulated/0/xiaying"

    private const val PREFS = "movieshell_prefs"
    private const val KEY_MIGRATED = "storage_migrated_to_public_v1"
    private const val REQ_CODE_LEGACY_STORAGE = 29078

    /** 公开根目录 */
    fun publicRoot(): File =
        runCatching { File(Environment.getExternalStorageDirectory(), ROOT_NAME) }
            .getOrNull()
            ?: File(PUBLIC_ROOT_PATH)

    /** 是否有写公开目录的权限 */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * 基准目录：有权限 → 公开目录；无权限 → 私有 filesDir/xiaying（回退）。
     * 别处不许再硬编码 filesDir/drpy 这类路径，一律走这里。
     */
    fun baseDir(context: Context): File =
        if (hasAccess(context)) publicRoot() else File(context.filesDir, ROOT_NAME)

    fun jsDir(context: Context): File = File(baseDir(context), "js").also { it.mkdirs() }
    fun pyDir(context: Context): File = File(baseDir(context), "py").also { it.mkdirs() }
    fun phpDir(context: Context): File = File(baseDir(context), "php").also { it.mkdirs() }
    fun webDir(context: Context): File = File(baseDir(context), "web").also { it.mkdirs() }

    /** v0.8.0 预留：订阅 spider jar 缓存 */
    fun jarDir(context: Context): File = File(baseDir(context), "jar").also { it.mkdirs() }
    fun backupDir(context: Context): File = File(baseDir(context), "backup").also { it.mkdirs() }
    fun logDir(context: Context): File = File(baseDir(context), "log").also { it.mkdirs() }

    /** v0.21.0：播放器截图保存位置 */
    fun screenshotsDir(context: Context): File =
        File(baseDir(context), "Screenshots").also { it.mkdirs() }

    /**
     * 申请存储权限。
     * API>=30：跳系统设置页（ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION）；
     * API<30：常规运行时权限申请。
     */
    fun requestAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${activity.packageName}"),
            )
            val ok = runCatching { activity.startActivity(intent); true }.getOrDefault(false)
            if (!ok) {
                // 部分 ROM 没有按包名的 action，退到通用页
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            LogBus.i(TAG, "已跳转系统设置申请所有文件访问权限")
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ),
                REQ_CODE_LEGACY_STORAGE,
            )
        }
    }

    /**
     * 升级迁移：filesDir/{drpy→js, py, php, web} 搬到公开目录对应子目录。
     *
     * - 幂等：prefs 标记，搬过就直接返回 true；
     * - 无权限时返回 false（不报错，调用方用回退目录继续初始化）；
     * - 搬运失败不删源文件，返回 false 并打日志（调用方 Toast 提示）。
     * @return true=已迁移（或无需迁移），false=未迁移
     */
    fun migrateFromPrivate(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return true
        if (!hasAccess(context)) {
            Log.w(TAG, "无存储权限，跳过迁移（用回退目录）")
            return false
        }
        return try {
            val moves = listOf(
                "drpy" to "js",
                "py" to "py",
                "php" to "php",
                "web" to "web",
            )
            var moved = 0
            for ((oldName, newName) in moves) {
                val src = File(context.filesDir, oldName)
                if (!src.isDirectory) continue
                val dst = File(publicRoot(), newName).also { it.mkdirs() }
                val files = src.listFiles() ?: emptyArray()
                for (f in files) {
                    val target = File(dst, f.name)
                    if (target.exists()) continue
                    if (f.isDirectory) f.copyRecursively(target) else f.copyTo(target)
                    moved++
                }
                // 目标里都有了才删源目录；有一个没搬过去就留着，不丢文件
                if (files.all { File(dst, it.name).exists() }) {
                    src.deleteRecursively()
                }
            }
            LogBus.i(TAG, "存储迁移完成，共搬运 $moved 个文件到 $PUBLIC_ROOT_PATH")
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            true
        } catch (e: Exception) {
            LogBus.e(TAG, "存储迁移失败：${e.message}")
            false
        }
    }
}
