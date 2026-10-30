package com.movieshell.data.repo

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
 * - key 只含 ASCII 安全字符（字母数字 . _ - : /）且不超长：沿用老算法结果（老文件/老副本继续命中）；
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
        if (LOSSLESS.matches(raw) && legacy.length <= maxLen) return legacy.ifBlank { fallback }
        val hash = sha256Hex(raw).take(12)
        val ext = if (keepExt) raw.substringAfterLast('.', "").takeIf { EXT.matches(it) } else null
        val stemRaw = if (ext != null) raw.dropLast(ext.length + 1) else raw
        val suffix = "_" + hash + (ext?.let { ".$it" } ?: "")
        val stem = stemRaw.replace(unsafe, "_").take((maxLen - suffix.length).coerceAtLeast(0))
        return stem + suffix
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
