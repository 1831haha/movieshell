package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * v0.16.1：嗅探自定义正则管理器（对标 DsPlayer"扩展正则"）。
 *
 * - 用户可在实验室页添加正则，匹配到的 URL 直接视为可播直链。
 * - 存 filesDir/sniff_patterns.json，数组元素为正则字符串。
 * - SniffEngine.sniffKind() 会先查自定义正则，命中即 DIRECT。
 */
object SniffPatternStore {

    private const val TAG = "SniffPatternStore"
    private const val FILE = "sniff_patterns.json"

    private var appContext: Context? = null
    private var cached: List<String>? = null
    // v0.246.0：编译结果缓存——原来 compiled() 每次调用都把全部正则重编译一遍，
    // 嗅探每个 URL 都调 sniffKind()，正则一多就是持续浪费。增删/保存时失效。
    private var compiledCache: List<Regex>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 所有自定义正则（字符串形式）。 */
    fun list(): List<String> {
        cached?.let { return it }
        val list = runCatching {
            val ctx = appContext ?: return emptyList()
            val f = java.io.File(ctx.filesDir, FILE)
            if (!f.isFile) return emptyList()
            val arr = JSONArray(f.readText())
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        }.onFailure {
            Log.w(TAG, "读取嗅探正则失败", it)
            // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
            appContext?.let { ctx -> AtomicFile.preserveCorrupt(java.io.File(ctx.filesDir, FILE)) }
        }.getOrDefault(emptyList())
        cached = list
        return list
    }

    /** 编译好的正则（忽略大小写），编译失败的跳过。结果缓存，增删/保存时失效。
     * v0.294.7（L3）：以 `!` 开头的条目是排除正则，不在这里返回，见 [excluded]。 */
    fun compiled(): List<Regex> {
        compiledCache?.let { return it }
        val compiled = list().filterNot { it.startsWith("!") }.mapNotNull { pattern ->
            runCatching { pattern.toRegex(RegexOption.IGNORE_CASE) }
                .onFailure { Log.w(TAG, "正则编译失败: $pattern") }
                .getOrNull()
        }
        compiledCache = compiled
        return compiled
    }

    @Volatile
    private var excludedCache: List<Regex>? = null

    /** v0.294.7（L3）：排除正则（条目以 `!` 开头）——命中的地址一律不当直链。 */
    fun excluded(): List<Regex> {
        excludedCache?.let { return it }
        val ex = list().filter { it.startsWith("!") }.mapNotNull { pattern ->
            runCatching { pattern.removePrefix("!").toRegex(RegexOption.IGNORE_CASE) }.getOrNull()
        }
        excludedCache = ex
        return ex
    }

    /** 添加一条正则，返回是否成功（含格式校验）。 */
    fun add(pattern: String): Boolean {
        val p = pattern.trim()
        if (p.isBlank()) return false
        // 先校验能编译
        runCatching { p.removePrefix("!").toRegex() }.onFailure { return false }
        if (p == "!") return false
        val cur = list().toMutableList()
        if (p in cur) return true // 已存在算成功
        cur += p
        return save(cur)
    }

    /** 删除一条正则。 */
    fun remove(pattern: String): Boolean {
        val cur = list().toMutableList()
        if (!cur.remove(pattern)) return false
        return save(cur)
    }

    private fun save(list: List<String>): Boolean {
        return runCatching {
            val ctx = appContext ?: return false
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            // v0.170.0：原子写盘，写盘失败如实返回 false
            val ok = AtomicFile.writeText(java.io.File(ctx.filesDir, FILE), arr.toString())
            if (!ok) return false
            cached = list
            compiledCache = null
            excludedCache = null
            LogBus.i(TAG, "嗅探正则已保存 ${list.size} 条")
            true
        }.onFailure {
            Log.w(TAG, "保存嗅探正则失败", it)
        }.getOrDefault(false)
    }
}
