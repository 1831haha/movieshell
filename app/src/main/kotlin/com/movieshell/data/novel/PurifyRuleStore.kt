package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
                .onFailure { Log.w(TAG, "净化规则读取失败", it) }
                .getOrDefault(NovelPurifyEngine.defaultRules().toMutableList())
        }
        if (!marker.isFile) {
            // seed 条件：storage 里没有任何 builtin 规则时才写入，不覆盖用户已有自定义规则
            if (base.none { it.builtin }) {
                for (r in NovelPurifyEngine.builtinRules()) {
                    if (base.none { it.name == r.name && it.regex == r.regex }) base.add(r)
                }
                writeAll(f, base)
            }
            runCatching { marker.createNewFile() }
        }
        base
    }

    suspend fun saveAll(context: Context, rules: List<PurifyRule>) =
        withContext(Dispatchers.IO) { writeAll(fileOf(context), rules) }

    suspend fun add(context: Context, rule: PurifyRule) = withContext(Dispatchers.IO) {
        val cur = list(context).toMutableList()
        cur.removeAll { it.name == rule.name && it.regex == rule.regex }
        cur.add(rule)
        writeAll(fileOf(context), cur)
    }

    suspend fun removeAt(context: Context, index: Int) = withContext(Dispatchers.IO) {
        val cur = list(context).toMutableList()
        if (index in cur.indices) {
            cur.removeAt(index)
            writeAll(fileOf(context), cur)
        }
    }

    suspend fun toggle(context: Context, index: Int, enable: Boolean) =
        withContext(Dispatchers.IO) {
            val cur = list(context).toMutableList()
            if (index in cur.indices) {
                cur[index] = cur[index].copy(enable = enable)
                writeAll(fileOf(context), cur)
            }
        }

    private fun writeAll(f: File, rules: List<PurifyRule>) = runCatching {
        f.writeText(json.encodeToString(ser, rules))
    }.onFailure { Log.w(TAG, "净化规则写入失败", it) }
}
