package com.movieshell.data.repo

import android.Manifest
import com.movieshell.ui.components.AppToast
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
 * - service/  服务插件工作目录（v0.128.0，见 serviceDir()，含回退口径）
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
    // v0.187.0 H15：v1 标记下未搬完的旧目录曾被误标为已迁移，换 v2 键重跑一次；
    // 已删干净的旧目录重跑是空操作，不重复搬运。
    private const val KEY_MIGRATED = "storage_migrated_to_public_v2"
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
     * v0.27.2：PY 依赖包（pylibs）私有目录。
     * 必须走私有 filesDir（不能走公开目录）：native .so 只能从私有目录 dlopen，
     * 公开目录（/sdcard）在部分 ROM 上 noexec。
     */
    fun pyLibsDir(context: Context): File =
        File(context.filesDir, "pylibs").also { it.mkdirs() }

    /** v0.23.0：live 直播源存放目录（xiaying/live/<id>.json）；直播页 v0.24 做，先存好 */
    fun liveDir(context: Context): File =
        File(baseDir(context), "live").also { it.mkdirs() }

    /** v0.22.0：server 插件安装根目录（xiaying/plugins/<id>/） */
    fun pluginsDir(context: Context): File =
        File(baseDir(context), "plugins").also { it.mkdirs() }

    /**
     * v0.192.0：往公开目录写 .nomedia（空文件），让系统媒体扫描跳过该目录。
     * 起因：magnet-stream 1.0.5 的 node_modules 里有 98 个 .ts 文件（96 个
     * .d.ts 类型定义 + 2 个源码 .ts），安卓把 .ts 当视频格式（MPEG-TS），
     * 相册里凭空多出一堆 0 秒空白“视频”。写失败只吞掉，不让安装/启动
     * 因为一个标记文件失败；已存在的不重复写。
     */
    fun ensureNomedia(dir: File) {
        runCatching {
            if (dir.isDirectory) {
                File(dir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
            }
        }
    }

    /**
     * v0.192.0：给已装插件补 .nomedia（启动时跑一次，幂等）。
     * 根目录 + 每个已装插件子目录各写一个：根目录的 .nomedia 负责挡住
     * 整棵树，子目录的是双保险——万一将来某个子目录被单独挪出去也不漏。
     * 只针对公开侧两个插件根（plugins/、service/）；私有 filesDir 下的
     * runtime/pylibs 等本来就不进媒体扫描，不碰。
     */
    fun ensureNomediaForPluginDirs(context: Context) {
        runCatching {
            listOf(pluginsDir(context), serviceDir(context)).forEach { root ->
                ensureNomedia(root)
                root.listFiles()?.filter { it.isDirectory }?.forEach { ensureNomedia(it) }
            }
        }
    }

    /** v0.137.0：runtime 型插件安装根（私有：native 载荷只能从私有目录执行）。 */
    fun runtimeDir(context: Context): File =
        File(context.filesDir, "runtime").also { it.mkdirs() }

    @Volatile
    private var serviceFallbackLogged = false

    /**
     * v0.128.0：服务插件工作目录根（xiaying/service/<id>/）。
     *
     * 服务脚本改经 /system/bin/sh 解释执行后不再依赖私有目录的 exec 权限，
     * 工作目录迁到公开目录方便用户直接管理脚本与数据。公共存储不可用
     * （目录建不了/不可写，如未授予所有文件访问权限）时回退旧私有根
     * filesDir/server_plugins，App 照常用、不崩。根目录解析只此一处，
     * ServicePluginManager 的安装/启动/卸载/自检全部走这里，不许散落硬编码。
     */
    fun serviceDir(context: Context): File {
        val pub = File(publicRoot(), "service")
        val usable = runCatching {
            pub.mkdirs()
            pub.isDirectory && pub.canWrite()
        }.getOrDefault(false)
        if (usable) return pub
        if (!serviceFallbackLogged) {
            serviceFallbackLogged = true
            LogBus.w(
                TAG,
                "公共服务目录不可用（${pub.absolutePath}），回退私有目录 filesDir/server_plugins",
            )
        }
        return File(context.filesDir, "server_plugins").also { it.mkdirs() }
    }

    /** v0.25.0：崩溃日志目录（xiaying/crash/crash_yyyyMMdd_HHmmss_SSS.txt，只留最近 20 个） */
    fun crashDir(context: Context): File =
        File(baseDir(context), "crash").also { it.mkdirs() }

    /** v0.26.0：离线下载根目录（xiaying/Download/<taskId>/） */
    fun downloadDir(context: Context): File =
        File(baseDir(context), "Download").also { it.mkdirs() }

    /**
     * v0.185.0：一键配置要建的全部目录（单一入口，改目录清单只改这里）。
     * 每个 dir() 本身已 mkdirs；py/book 与 php/t4_demo/lib 是子目录，
     * 必须显式列出，单调父目录不会自动带出它们。
     * v0.188.0：补 js/subs、py/subs（订阅展开时才建的两个目录，现在 setup
     * 一并建好，L24）；私有侧安装根（runtime/、pylibs/、mpv-libs/、
     * subtitle-plugin/）不在此清单——它们是各插件自己的私有安装目录，
     * 由各自安装逻辑建，不属公开侧一键配置。
     * 注意：只建目录、不写文件、不删文件；写说明文档是 PluginMarket
     * 的 setup 分支的事，文档内容来自插件包、不进安装包。
     */
    fun setupDirs(context: Context): List<File> = listOf(
        jsDir(context),
        File(jsDir(context), "subs").also { it.mkdirs() },
        pyDir(context),
        File(pyDir(context), "book").also { it.mkdirs() },
        File(pyDir(context), "subs").also { it.mkdirs() },
        phpDir(context),
        File(phpDir(context), "t4_demo").also { it.mkdirs() },
        File(File(phpDir(context), "t4_demo"), "lib").also { it.mkdirs() },
        webDir(context),
        jarDir(context),
        liveDir(context),
        pluginsDir(context),
        serviceDir(context),
        backupDir(context),
        logDir(context),
        crashDir(context),
        downloadDir(context),
        screenshotsDir(context),
    )

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

    /** 私有回退根：无权限时 baseDir() 的取值，即 filesDir 下的 xiaying 目录。 */
    fun privateRoot(context: Context): File = File(context.filesDir, ROOT_NAME)

    /**
     * v0.187.0 H15：把一条存量路径换算成以逻辑根 xiaying 为基准的相对路径。
     * 只剥离已知根前缀：当前公开根、旧公开根、旧私有根、旧私有散落目录
     * （filesDir 下的 drpy、py、php、web，其中 drpy 对应公开侧的 js）。
     * 网络地址、已是相对路径、以及不在已知根下的绝对路径原样返回，不乱改。
     */
    fun toRelativePath(context: Context, stored: String): String {
        val path = stored.trim()
        if (path.isEmpty()) return path
        if (path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        ) return path
        if (!path.startsWith("/")) return path
        val roots = listOf(
            publicRoot().absolutePath,
            PUBLIC_ROOT_PATH,
            privateRoot(context).absolutePath,
        )
        for (root in roots) {
            val prefix = root.trimEnd('/') + "/"
            if (path.startsWith(prefix)) return path.removePrefix(prefix)
            if (path == root) return ""
        }
        val filesPrefix = context.filesDir.absolutePath.trimEnd('/') + "/"
        if (path.startsWith(filesPrefix)) {
            val rest = path.removePrefix(filesPrefix)
            val first = rest.substringBefore("/")
            val mapped = when (first) {
                "drpy" -> "js"
                "py", "php", "web" -> first
                else -> null
            }
            if (mapped != null) {
                val tail = rest.substringAfter("/", "")
                return if (tail.isEmpty()) mapped else "$mapped/$tail"
            }
        }
        return path
    }

    /**
     * v0.187.0 H15：读取时把存量路径拼回当前根。
     * 相对路径直接拼当前 baseDir；绝对路径先剥已知根再拼当前根，
     * 权限一换根目录，同一条记录仍指向同一逻辑位置；不在已知根下
     * 的绝对路径与网络地址原样返回。
     */
    fun resolveStoredPath(context: Context, stored: String): String {
        val path = stored.trim()
        if (path.isEmpty()) return path
        if (path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        ) return path
        if (!path.startsWith("/")) return File(baseDir(context), path).absolutePath
        val rel = toRelativePath(context, path)
        if (rel != path) return File(baseDir(context), rel).absolutePath
        return path
    }

    /**
     * v0.187.0 H15：当前根下找不到时，另一根（公开与私有互为另一根）下
     * 是否还有同名相对路径文件。权限切换导致当前根暂时看不到文件时，
     * 调用方凭此不把记录误判为文件已丢失。
     */
    fun existsUnderOtherRoot(context: Context, stored: String): Boolean {
        val rel = toRelativePath(context, stored)
        if (rel.isEmpty() || rel == stored.trim()) return false
        val other = if (baseDir(context) == publicRoot()) privateRoot(context) else publicRoot()
        return File(other, rel).isFile
    }

    /** 流式 SHA-256（写法照 ServicePluginManager 的 hashStream，不整文件进内存）。 */
    // 290（Q5）：原来这里和 ExecutableTrust.sha256 是逐行相同的实现，
    // 改一处漏另一处就是体检说的“改漏”；直接委托过去，只留一个实现，
    // 签名（返回 String?，失败为 null）和调用点一行不动。
    private fun sha256Of(file: File): String? = ExecutableTrust.sha256(file)

    /**
     * 内容是否一致：长度先比，长度相等再比 SHA-256；摘要算不出来时
     * 回退到修改时间相等才算一致。
     */
    private fun sameFileContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        val ha = sha256Of(a)
        val hb = sha256Of(b)
        if (ha != null && hb != null) return ha == hb
        return a.lastModified() == b.lastModified()
    }

    /**
     * v0.187.0 H15：把 src 一项合并进 dst，目录递归合并。
     * 返回值：1=本次复制过（目录时为子树内复制总数），0=目标已有且
     * 内容一致或目标较新、无需复制，-1=未搬完（调用方必须保留源目录）。
     * 判定：目标不存在才复制；目标已存在时先比内容，一致则不动；
     * 内容不同时留较新者——源较新则覆盖，目标较新则保留目标。
     */
    private fun migrateEntry(src: File, dst: File): Int {
        if (src.isDirectory) {
            if (dst.exists() && !dst.isDirectory) {
                LogBus.w(TAG, "迁移类型冲突，源是目录、目标是文件，已保留源：${src.absolutePath}")
                return -1
            }
            dst.mkdirs()
            if (!dst.isDirectory) return -1
            val children = src.listFiles() ?: emptyArray()
            var copied = 0
            var failed = false
            for (child in children) {
                val r = migrateEntry(child, File(dst, child.name))
                if (r < 0) failed = true else copied += r
            }
            return if (failed) -1 else copied
        }
        if (!src.isFile) {
            LogBus.w(TAG, "迁移跳过非常规文件，已保留源：${src.absolutePath}")
            return -1
        }
        if (dst.exists() && dst.isDirectory) {
            LogBus.w(TAG, "迁移类型冲突，源是文件、目标是目录，已保留源：${src.absolutePath}")
            return -1
        }
        if (!dst.exists()) {
            val ok = runCatching {
                src.copyTo(dst, overwrite = true)
                dst.isFile && dst.length() == src.length()
            }.getOrDefault(false)
            return if (ok) 1 else -1
        }
        if (sameFileContent(src, dst)) return 0
        if (src.lastModified() > dst.lastModified()) {
            val ok = runCatching {
                src.copyTo(dst, overwrite = true)
                dst.isFile && dst.length() == src.length() &&
                    (sha256Of(src) == null || sha256Of(dst) == null ||
                        sha256Of(src) == sha256Of(dst))
            }.getOrDefault(false)
            if (!ok) {
                LogBus.w(TAG, "较新源文件覆盖失败，已保留源：${src.absolutePath}")
                return -1
            }
            LogBus.i(TAG, "较新源文件已覆盖目标：${dst.absolutePath}")
            return 1
        }
        LogBus.i(TAG, "目标文件较新，保留目标、跳过旧源：${dst.absolutePath}")
        return 0
    }

    /**
     * 升级迁移：filesDir/{drpy→js, py, php, web} 搬到公开目录对应子目录。
     *
     * - 幂等：prefs 标记，搬过就直接返回 true；
     * - 无权限时返回 false（不报错，调用方用回退目录继续初始化）；
     * - 判定不再以目标存在为准：同名文件按内容 SHA-256 与修改时间
     *   比新旧，留较新者；只有整目录全部确认已迁移（内容一致、
     *   已覆盖较新者、或目标本就较新）才删源目录，否则整目录保留
     *   并返回 false，下次启动重跑，不丢文件；
     * - 本次确实搬了文件时 Toast 一次，写清旧位置与新位置。
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
            var allSafe = true
            for ((oldName, newName) in moves) {
                val src = File(context.filesDir, oldName)
                if (!src.isDirectory) continue
                val dst = File(publicRoot(), newName).also { it.mkdirs() }
                val entries = src.listFiles() ?: emptyArray()
                var copiedHere = 0
                var dirSafe = true
                for (f in entries) {
                    val r = migrateEntry(f, File(dst, f.name))
                    if (r < 0) dirSafe = false else copiedHere += r
                }
                if (dirSafe) {
                    src.deleteRecursively()
                    moved += copiedHere
                } else {
                    allSafe = false
                    LogBus.w(TAG, "旧目录未搬完，已保留不删除：${src.absolutePath}")
                }
            }
            if (!allSafe) {
                LogBus.w(TAG, "存储迁移未完成，旧文件保留在原处，下次启动重试")
                return false
            }
            if (moved > 0) {
                runCatching {
                    AppToast.show(context, "存储位置已切换：旧文件从 ${context.filesDir.absolutePath} 下的 drpy、py、php、web 迁移到 ${publicRoot().absolutePath}", long = true)
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

    private const val KEY_PERM_SKIPPED = "storage_perm_prompt_skipped"

    /** v0.294.5：启动时的存储权限引导是否已被用户跳过（跳过一次就不再每次启动都弹） */
    fun isPermPromptSkipped(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PERM_SKIPPED, false)

    fun setPermPromptSkipped(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PERM_SKIPPED, true).apply()
    }

    /**
     * v0.294.5：授权前导入的文件“消失”修复。无权限时 baseDir() 回退到私有
     * filesDir/xiaying，授权后根目录换成公开目录，私有回退里的文件就看不到了。
     * 有权限时把私有回退根整棵并进公开根：目标不存在才复制（绝不覆盖已有文件），
     * 复制成功或内容一致才删源；同名不同内容的保留在原处并记日志，不丢任何一份。
     * 幂等：私有回退根不存在或已空时直接返回。
     * @return 本次并过去的文件数
     */
    @Synchronized
    fun mergePrivateRootIntoPublic(context: Context): Int {
        if (!hasAccess(context)) return 0
        val src = privateRoot(context)
        if (!src.isDirectory) return 0
        val dst = publicRoot()
        if (runCatching { src.canonicalPath == dst.canonicalPath }.getOrDefault(true)) return 0
        var merged = 0
        var kept = 0
        fun walk(s: File, d: File) {
            val children = s.listFiles() ?: return
            for (c in children) {
                val t = File(d, c.name)
                if (c.isDirectory) {
                    if (t.exists() && !t.isDirectory) { kept++; continue }
                    t.mkdirs()
                    walk(c, t)
                    // 子目录搬空了就删掉
                    if (c.listFiles()?.isEmpty() == true) c.delete()
                } else if (c.isFile) {
                    if (!t.exists()) {
                        val ok = runCatching {
                            c.copyTo(t, overwrite = false)
                            t.isFile && t.length() == c.length()
                        }.getOrDefault(false)
                        if (ok) { c.delete(); merged++ } else kept++
                    } else if (t.isFile && sameFileContent(c, t)) {
                        c.delete()
                    } else {
                        kept++
                        LogBus.w(TAG, "同名文件内容不同，保留私有目录里的旧份：${c.absolutePath}")
                    }
                }
            }
        }
        return try {
            walk(src, dst)
            if (src.listFiles()?.isEmpty() == true) src.delete()
            if (merged > 0) LogBus.i(TAG, "授权后合并私有回退目录：并入 $merged 个文件到 ${dst.absolutePath}（保留 $kept 个冲突项）")
            merged
        } catch (e: Exception) {
            LogBus.e(TAG, "合并私有回退目录失败：${e.message}")
            0
        }
    }
}
