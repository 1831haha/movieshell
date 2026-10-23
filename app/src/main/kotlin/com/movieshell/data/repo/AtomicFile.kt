package com.movieshell.data.repo

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * v0.170.0：共享原子写盘 + 损坏文件保全，各 JSON store 共用，不许各写一套。
 *
 * 写盘：临时文件 + fsync + 同目录改名替换。进程中途被杀只会留下一个无害的 .tmp，
 * 正式文件要么是旧的完整版、要么是新的完整版，绝不会是写了一半的截断 JSON。
 * （防的是 App 进程死亡；断电级别的持久化还需目录 fsync，本处不做。）
 *
 * 读坏：解析失败的 store 调 preserveCorrupt() 先把原文件拷一份为
 * <原名>.corrupt.bak 再按空处理——旧字节留作恢复证据，下次写盘不再是"悄悄清空"。
 * 备份只有一个文件，后一次损坏会覆盖前一次；缓存类文件（首页缓存等可再生数据）不用保全。
 */
object AtomicFile {
    private const val TAG = "AtomicFile"

    /** 原子写盘：成功返回 true，失败返回 false（不抛异常，调用方凭返回值报错）。 */
    fun writeText(file: File, text: String): Boolean {
        return try {
            val dir = file.parentFile ?: return false
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val tmp = File(dir, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.channel.force(true)
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                Log.w(TAG, "改名失败：${file.name}")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "写盘失败：${file.name}", e)
            false
        }
    }

    /** 损坏文件保全：原文件拷为 <原名>.corrupt.bak（覆盖旧备份），失败只记日志。 */
    fun preserveCorrupt(file: File): Boolean {
        return try {
            if (!file.isFile) return false
            val dir = file.parentFile ?: return false
            file.copyTo(File(dir, "${file.name}.corrupt.bak"), overwrite = true)
            Log.w(TAG, "损坏文件已保全：${file.name}.corrupt.bak")
            true
        } catch (e: Exception) {
            Log.w(TAG, "损坏文件保全失败：${file.name}", e)
            false
        }
    }
}
