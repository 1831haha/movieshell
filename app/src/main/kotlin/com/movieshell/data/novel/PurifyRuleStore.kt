package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.95.0 净化规则持久化：落在 filesDir/novel_purify_rules.json。
 * 首次使用时写入内置默认规则；全部 suspend，走 Dispatchers.IO。
 */
object PurifyRuleStore {

    private const val TAG = "PurifyRuleStore"
    private const val FILE_NAME = "novel_purify_rules.json"

    /** v0.102.0：内置规则补种标记。用户删掉内置规则后不再自动补回来（删了就是删了）。 */
    private const val SEED_MARKER = "novel_purify_rules.seeded"

    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(PurifyRule.serializer())

    // v0.216.0（C9）：add/removeAt/toggle/saveAll 读-改-写串行化（list() 本身
    // 不取锁，互调不死锁；种子补种是一次性幂等写，不在锁内也无害）
    private val mutex = Mutex()

    private fun fileOf(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * 读规则。
     * v0.102.0 P2-3：首次运行时补种内置规则库——storage 里没有任何 builtin 规则时才写入，
     * 不覆盖用户已有自定义规则；写一次补种标记，用户删掉内置规则后不再自动补回来。
     * 老数据无 builtin 字段时按 false 解码（PurifyRule 有默认值），照常触发补种。
     */
    suspend fun list(context: Context): List<PurifyRule> = withContext(Dispatchers.IO) {
        val f = fileOf(context)
        val marker = File(context.filesDir, SEED_MARKER)
        val base: MutableList<PurifyRule> = if (!f.isFile) {
            NovelPurifyEngine.defaultRules().toMutableList()
        } else {
            runCatching { json.decodeFromString(ser, f.readText()).toMutableList() }
                .onFailure {
                    Log.w(TAG, "净化规则读取失败", it)
                    // v0.170.0：读坏先保全原文件——默认规则补种写盘不再悄悄清空用户规则
                    AtomicFile.preserveCorrupt(f)
                }
                .getOrDefault(NovelPurifyEngine.defaultRules().toMutableList())
        }
        if (!marker.isFile) {
            // v0.247.0（F27）：补种写盘失败时不再建标记——原来不管写没写成
            // 都建标记，下次启动见标记跳过补种，内置规则永久丢失；现在只有
            // “无需补种（已有 builtin）或补种写盘成功”才建标记，失败下次重试。
            // 补种本身按名+正则去重，失败重试幂等。
            // 注意：AtomicFile.writeText 永不抛异常，失败只返回 false；
            // writeAll 返回的是 Result<Boolean>，isSuccess 恒真、不能当成功用，
            // 必须取 Boolean 值（第 1 轮误用 isSuccess，写盘返回 false 时
            // 仍会建标记，F27 本来没修住）。getOrDefault(false) 同时覆盖
            // “写盘返回 false”与“序列化抛异常”两路。
            val seeded = if (base.none { it.builtin }) {
                for (r in NovelPurifyEngine.builtinRules()) {
                    if (base.none { it.name == r.name && it.regex == r.regex }) base.add(r)
                }
                writeAll(f, base).getOrDefault(false)
            } else true
            if (seeded) runCatching { marker.createNewFile() }
        }
        base
    }

    suspend fun saveAll(context: Context, rules: List<PurifyRule>) =
        withContext(Dispatchers.IO) { mutex.withLock { writeAll(fileOf(context), rules) } }

    suspend fun add(context: Context, rule: PurifyRule) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cur = list(context).toMutableList()
            cur.removeAll { it.name == rule.name && it.regex == rule.regex }
            cur.add(rule)
            writeAll(fileOf(context), cur)
        }
    }

    suspend fun removeAt(context: Context, index: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cur = list(context).toMutableList()
            if (index in cur.indices) {
                cur.removeAt(index)
                writeAll(fileOf(context), cur)
            }
        }
    }

    suspend fun toggle(context: Context, index: Int, enable: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cur = list(context).toMutableList()
                if (index in cur.indices) {
                    cur[index] = cur[index].copy(enable = enable)
                    writeAll(fileOf(context), cur)
                }
            }
        }

    private fun writeAll(f: File, rules: List<PurifyRule>) = runCatching {
        // v0.170.0：原子写盘
        AtomicFile.writeText(f, json.encodeToString(ser, rules))
    }.onFailure { Log.w(TAG, "净化规则写入失败", it) }
}
