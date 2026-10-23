package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.util.UUID

/**
 * v0.100.0 洛雪音乐源持久化：落在 filesDir/music_sources.json。
 * 全部 suspend，走 Dispatchers.IO；异常记 LogBus，不抛。
 */
@Serializable
data class MusicSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "", // LX 脚本 URL；内置源 url 为空
    val builtin: Boolean = false, // 内置 huibq 源不可删
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
)

object MusicSourceStore {

    private const val TAG = "MusicSourceStore"
    private const val FILE_NAME = "music_sources.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(MusicSource.serializer())

    /**
     * v0.186.0：音源注册表读改写同一把锁；公开函数互调走无锁 helper，不重复取锁。
     */
    private val mutex = Mutex()

    private fun fileOf(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun seeds(): List<MusicSource> =
        listOf(MusicSource(name = "huibq（内置）", builtin = true))

    /**
     * v0.186.0：无锁内部读——Mutex 不可重入，add/remove/setEnabled/addLocal 互调公开函数会死锁，
     * 故公开函数先取锁再调它；它本身只做文件读写，不取锁、不切线程，调用方已在 IO 线程。
     */
    private fun loadUnlocked(context: Context): List<MusicSource> {
        val f = fileOf(context)
        if (!f.isFile) {
            val s = seeds()
            writeAll(f, s)
            return s
        }
        return runCatching { json.decodeFromString(ser, f.readText()) }
            .onFailure {
                LogBus.w(TAG, "音乐源读取失败：${it.message}")
                // v0.170.0：读坏先保全原文件，种子源写盘不再悄悄清空旧数据
                AtomicFile.preserveCorrupt(f)
            }
            .getOrDefault(seeds())
    }

    private fun addUnlocked(context: Context, source: MusicSource): Boolean {
        return runCatching {
            val cur = loadUnlocked(context).toMutableList()
            cur.removeAll { it.id == source.id }
            cur.add(source)
            writeAll(fileOf(context), cur)
        }.onFailure { LogBus.w(TAG, "音乐源添加失败：${it.message}") }.getOrDefault(false)
    }

    /** 读全部；首次加载（或读坏）时种入内置源。 */
    suspend fun list(context: Context): List<MusicSource> = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadUnlocked(context)
        }
    }

    suspend fun add(context: Context, source: MusicSource) = mutex.withLock {
        withContext(Dispatchers.IO) {
            addUnlocked(context, source)
        }
    }

    /** v0.103.0：本地 .js 文件导入——拷贝到 filesDir/lx_sources/<uuid>.js，url 用 file:// scheme。 */
    suspend fun addLocal(context: Context, name: String, srcFile: File): MusicSource? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "lx_sources")
                if (!dir.isDirectory) dir.mkdirs()
                val dest = File(dir, "${UUID.randomUUID()}.js")
                srcFile.copyTo(dest, overwrite = true)
                val source = MusicSource(
                    name = name,
                    url = "file://${dest.absolutePath}",
                    enabled = true,
                )
                // 注册表写盘失败返回空：调用方已按空判导入失败并提示
                val ok = mutex.withLock { addUnlocked(context, source) }
                if (ok) source else null
            }.onFailure { LogBus.w(TAG, "本地音源导入失败：${it.message}") }
                .getOrNull()
        }

    /**
     * 内置源拒绝删除。url 以 file:// 开头时顺手删对应本地文件
     * （runCatching 包住，删不掉不报错）。
     */
    suspend fun remove(context: Context, id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val cur = loadUnlocked(context).toMutableList()
                val target = cur.firstOrNull { it.id == id } ?: return@runCatching
                if (target.builtin) {
                    LogBus.w(TAG, "内置源不可删除：${target.name}")
                    return@runCatching
                }
                if (target.url.startsWith("file://")) {
                    runCatching { File(URI(target.url)).delete() }
                        .onFailure { LogBus.w(TAG, "本地脚本删除失败：${it.message}") }
                }
                cur.remove(target)
                writeAll(fileOf(context), cur)
            }.onFailure { LogBus.w(TAG, "音乐源删除失败：${it.message}") }
        }
    }

    suspend fun setEnabled(context: Context, id: String, enabled: Boolean) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val cur = loadUnlocked(context).toMutableList()
                    val i = cur.indexOfFirst { it.id == id }
                    if (i >= 0) {
                        cur[i] = cur[i].copy(enabled = enabled)
                        writeAll(fileOf(context), cur)
                    }
                }.onFailure { LogBus.w(TAG, "音乐源开关失败：${it.message}") }
            }
        }

    private fun writeAll(f: File, sources: List<MusicSource>): Boolean {
        val ok = runCatching {
            // v0.170.0：原子写盘
            AtomicFile.writeText(f, json.encodeToString(ser, sources))
        }.onFailure { LogBus.w(TAG, "音乐源写入失败：${it.message}") }.getOrDefault(false)
        if (!ok) LogBus.w(TAG, "音乐源写入失败")
        return ok
    }
}
