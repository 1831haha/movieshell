package com.movieshell.data.repo

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * v0.170.0：共享原子写盘 + 损坏文件保全，各 JSON store 共用，不许各写一套。
 *
 * 写盘：临时文件 + fsync + 同目录改名替换。进程中途被杀只会留下一个无害的 tmp，
 * 正式文件要么是旧的完整版、要么是新的完整版，绝不会是写了一半的截断 JSON。
 * （防的是 App 进程死亡；断电级别的持久化还需目录 fsync，本处不做。）
 *
 * v0.186.0：tmp 名加随机串，两个线程同时写同一 store 时各写各的 tmp、互不截断；
 * 改名仍是同目录 renameTo，正式文件一次只被一个完整内容替换。
 * 被杀留下的 tmp 是无害孤儿，不在此处顺手清理——清理会误删并发写盘者的 tmp。
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
            val tmp = File(dir, "${file.name}.${UUID.randomUUID()}.tmp")
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

    /**
     * v0.247.0（DS12）：已写好的临时文件替换目标文件——旧目标先改名备份，
     * 落位成功后清备份；落位失败把备份恢复回去。
     * 返回是否成功；失败时 part 留着供调用方重试或续传。准确表述：目标路径
     * 在“旧目标已移为备份、新文件尚未落位”之间会暂时缺失；落位失败时恢复
     * 备份，恢复也失败则缺失持续到下次替换（下次替换先恢复备份），只在
     * 两次文件系统操作连续失败时才出现，不存在静默丢数据的单点失败。
     * 残留处理：目标缺失、备份还在（上次收尾中途失败）时先恢复备份；
     * 恢复失败也继续落位新文件（旧目标本来就已不可见），只记日志；
     * 成功落位后清备份失败只记日志，下次替换先清残留备份。
     * 调用方约束：part、dst、备份必须在同一目录（同文件系统），否则改名无意义。
     * 三处调用点对 false 的处理：分片/直链下载抛错（任务失败重试）、
     * 订阅规则下载删 part 后抛错（旧目标已恢复或备份留待下次恢复）。
     */
    fun replaceFile(part: File, dst: File): Boolean {
        return try {
            val dir = dst.parentFile ?: return false
            val bak = File(dir, dst.name + ".bak")
            if (!dst.isFile && bak.isFile) {
                if (!bak.renameTo(dst)) {
                    Log.w(TAG, "旧目标备份恢复失败，继续落位新文件：${dst.name}")
                }
            }
            if (dst.isFile) {
                if (bak.isFile && !bak.delete()) {
                    Log.w(TAG, "残留备份清理失败，放弃替换：${dst.name}")
                    return false
                }
                if (!dst.renameTo(bak)) {
                    Log.w(TAG, "旧目标备份失败，放弃替换：${dst.name}")
                    return false
                }
            }
            if (!part.renameTo(dst)) {
                if (bak.isFile && !bak.renameTo(dst)) {
                    Log.w(TAG, "落位失败且旧目标恢复失败，目标缺失、备份残留：${dst.name}")
                }
                return false
            }
            if (bak.isFile && !bak.delete()) {
                Log.w(TAG, "旧目标备份清理失败，残留 .bak：${bak.absolutePath}")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "替换失败：${dst.name}", e)
            false
        }
    }
}
