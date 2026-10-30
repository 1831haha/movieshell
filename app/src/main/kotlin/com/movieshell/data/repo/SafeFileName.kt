package com.movieshell.data.repo

import java.io.File
import java.security.MessageDigest

/**
 * v0.294.1：把任意 key（规则文件名、源 key、分类 id）转成安全文件名，且保证“不同 key → 不同文件名”。
 *
 * 根因：原来各处直接 `key.replace(Regex("[^A-Za-z0-9._-]+"), "_")`，中文全被吃成下划线——
 * 「py:红果短剧.py」「py:西瓜影视.py」「py:央视.py」都变成 `py_.py`，共用同一个可信副本路径；
 * Python 桥按路径缓存 Spider 实例，于是换到任何中文名 PY 规则都还在跑第一个加载的那个，
 * 首页缓存（HomeCache）同理串台。
 *
 * 规则：
 * - key 一个字符都不用替换且不超长：原样作文件名；
 * - v0.294.5 起：只要有字符被替换（含 . : /），一律追加摘要，杜绝「py:a」「py_a」同名；
 * - 否则（含中文、空格等，或超长）：老算法结果截断 + `_` + key 的 SHA-256 前 12 位，保证唯一；
 * - keepExt=true 时保留原扩展名（Python 按 .py 后缀选 loader，不能丢）。
 */
object SafeFileName {
    private val LOSSLESS = Regex("^[A-Za-z0-9._:/-]*$")
    private val EXT = Regex("^[A-Za-z0-9]{1,8}$")

    fun encode(
        raw: String,
        unsafe: Regex,
        maxLen: Int,
        fallback: String,
        keepExt: Boolean = false,
    ): String {
        val legacy = raw.replace(unsafe, "_")
        // v0.294.5：“无损”只认“一个字符都没被替换”。原来 LOSSLESS 把 . : / 也算安全，
        // 但各调用方的 unsafe 正则会把它们换成 `_`，于是「py:a」「py_a」、「a.b」「a_b」
        // 撞到同一个文件。现在只要有任何字符被替换就追加摘要，不同 key 永不同名。
        if (legacy == raw && legacy.length <= maxLen) return legacy.ifBlank { fallback }
        val hash = sha256Hex(raw).take(12)
        val ext = if (keepExt) raw.substringAfterLast('.', "").takeIf { EXT.matches(it) } else null
        val stemRaw = if (ext != null) raw.dropLast(ext.length + 1) else raw
        val suffix = "_" + hash + (ext?.let { ".$it" } ?: "")
        val stem = stemRaw.replace(unsafe, "_").take((maxLen - suffix.length).coerceAtLeast(0))
        return stem + suffix
    }

    /** v0.294.5：旧版（≤v0.294.4）算法结果，仅供老文件迁移查找用，新文件不要用。 */
    fun legacyEncode(
        raw: String,
        unsafe: Regex,
        maxLen: Int,
        fallback: String,
        keepExt: Boolean = false,
    ): String {
        val legacy = raw.replace(unsafe, "_")
        if (LOSSLESS.matches(raw) && legacy.length <= maxLen) return legacy.ifBlank { fallback }
        return encode(raw, unsafe, maxLen, fallback, keepExt)
    }

    private val IMPORT_BAD = Regex("[\\u0000-\\u001f\\u007f:*?\"<>|]")

    /**
     * v0.294.5：导入文件名清洗（防路径穿越）。系统选择器给的 DISPLAY_NAME 理论上可以是
     * 「../../xx.py」「a/b.js」这类带路径的串：只取最后一段，去掉控制符与 Windows/FAT 非法字符，
     * 去掉开头的点（不产生 . / .. / 隐藏文件），中文原样保留；清洗后为空用 fallback。
     */
    fun importName(raw: String?, fallback: String, maxLen: Int = 120): String {
        var n = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
        n = n.replace(IMPORT_BAD, "_").trim().trimStart('.').trim()
        if (n.isEmpty() || n == "." || n == "..") return fallback
        if (n.length > maxLen) {
            val ext = n.substringAfterLast('.', "").takeIf { EXT.matches(it) }
            n = if (ext != null) n.dropLast(ext.length + 1).take(maxLen - ext.length - 1) + "." + ext else n.take(maxLen)
        }
        return n
    }

    /**
     * v0.294.5：dir 下的子文件，规范化路径必须仍在 dir 里，否则返回 null（拒绝写）。
     */
    fun childInside(dir: File, name: String): File? = runCatching {
        val base = dir.canonicalFile
        val f = File(base, name).canonicalFile
        if (f.parentFile == base) f else null
    }.getOrNull()

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
