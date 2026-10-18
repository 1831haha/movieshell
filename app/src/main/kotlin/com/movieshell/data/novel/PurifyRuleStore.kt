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

    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(PurifyRule.serializer())

    private fun fileOf(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 读规则；文件不存在时种入默认规则并返回。 */
    suspend fun list(context: Context): List<PurifyRule> = withContext(Dispatchers.IO) {
        val f = fileOf(context)
        if (!f.isFile) {
            val defaults = NovelPurifyEngine.defaultRules()
            writeAll(f, defaults)
            return@withContext defaults
        }
        runCatching { json.decodeFromString(ser, f.readText()) }
            .onFailure { Log.w(TAG, "净化规则读取失败", it) }
            .getOrDefault(NovelPurifyEngine.defaultRules())
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
