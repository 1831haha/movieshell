package com.movieshell.data.repo

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * v0.218.0（S22）：zip 安装共用工具——原来插件市场 PluginMarket 与
 * 服务插件管理器 ServicePluginManager 各有一套解压防护：条目上限 2000、
 * 解压后 500MB 上限、safeDst 防路径逃逸、暂存目录换入旧版的 swap，
 * 连注释都写着“与对方同口径”；改上限要改两处，漏一处就是防护缺口。
 * 现在同一套上限常量与 swap 逻辑只此一处。
 */
object ZipInstall {

    /** v0.186.0：解压上限（H14）。条目数按实际解压的文件计，上限 2000；解压后总字节数按实际写出的字节累计，上限 500MB，超限即中止并清暂存。 */
    const val MAX_ZIP_ENTRIES = 2000
    const val MAX_UNPACKED_BYTES = 500L * 1024L * 1024L

    private const val TAG = "ZipInstall"

    /** v0.186.0：解压预算累计器（条目数与解压后字节数，跨分支共用一个实例）。 */
    class UnpackBudget {
        var entries = 0
        var bytes = 0L
    }

    /** zip-slip 防护：解压目标必须落在 dir 内。 */
    fun safeDst(dir: File, name: String): File {
        val dst = File(dir, name)
        if (!dst.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            throw IllegalStateException("插件包路径非法：$name")
        }
        return dst
    }

    /**
     * v0.171.0：安装暂存目录（与目标同父目录，保证改名替换不跨文件系统）。
     * 命名带时间戳，避免上次失败残留的暂存目录与本次冲突。
     */
    fun stagingDir(root: File): File {
        val parent = root.parentFile ?: throw IllegalStateException("插件目录无父目录：${root.path}")
        return File(parent, "${root.name}.staging-${System.currentTimeMillis()}")
    }

    /**
     * v0.171.0：暂存目录整体替换目标目录。先把旧目录改名备份，再把暂存目录
     * 改名到位，最后删备份；改名失败时把备份改回来，旧版不丢。
     * 调用方须保证 staging 已完整解压；本函数只做替换，不做内容校验。
     */
    fun swapDir(root: File, staging: File) {
        val parent = root.parentFile ?: throw IllegalStateException("插件目录无父目录：${root.path}")
        val backup = File(parent, "${root.name}.bak-${System.currentTimeMillis()}")
        val hadOld = root.exists()
        if (hadOld && !root.renameTo(backup)) {
            throw IllegalStateException("旧版备份失败，安装已中止，旧版未动：${root.name}")
        }
        try {
            if (!staging.renameTo(root)) {
                throw IllegalStateException("新版落盘改名失败，安装已中止")
            }
            if (hadOld) runCatching { backup.deleteRecursively() }
        } catch (e: Exception) {
            if (hadOld && !root.exists()) {
                runCatching { backup.renameTo(root) }
                    .onFailure { LogBus.e(TAG, "旧版回滚失败，备份留在：${backup.path}") }
            }
            throw e
        }
    }

    /**
     * v0.186.0：带解压预算的单条目拷贝（H14）。调用方只负责把条目解到暂存区，
     * 本函数累计条目数与实际写出字节数，超限即抛异常，调用方清暂存目录；
     * 暂存区里的半截文件随暂存目录一起删，不会落到目标目录。
     */
    fun copyEntryBounded(
        zf: ZipFile,
        e: ZipEntry,
        dst: File,
        budget: UnpackBudget,
    ) {
        if (++budget.entries > MAX_ZIP_ENTRIES) {
            throw IllegalStateException("插件包解压条目数超限（上限 $MAX_ZIP_ENTRIES）")
        }
        dst.parentFile?.mkdirs()
        zf.getInputStream(e).use { input ->
            dst.outputStream().use { out ->
                val buf = ByteArray(32 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    if (n == 0) continue
                    budget.bytes += n
                    if (budget.bytes > MAX_UNPACKED_BYTES) {
                        throw IllegalStateException("插件包解压后大小超限（上限 500MB）")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
    }
}
