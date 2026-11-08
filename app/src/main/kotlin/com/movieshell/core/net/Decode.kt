package com.movieshell.core.net

import java.nio.charset.Charset

/**
 * v1.1.6 分层第 2 批：页面解码——先 UTF-8，出现替换符则转 GBK（中文小说站常见）。
 * 原 BookRuleEngine.decodeBytes，CSS 引擎与 JS 引擎共用，放 core/net。
 */
internal fun decodeBytes(bytes: ByteArray): String {
    val utf8 = bytes.toString(Charsets.UTF_8)
    return if (utf8.contains('�')) bytes.toString(Charset.forName("GBK")) else utf8
}
