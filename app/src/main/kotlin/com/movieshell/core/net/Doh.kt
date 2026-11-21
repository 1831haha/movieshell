package com.movieshell.core.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * v1.4.8 A4：DoH（DNS over HTTPS）纯函数与常量，不碰 Android。
 *
 * - endpoint 须是完整 URL：https scheme，且带路径（根路径不算）；
 * - 内置三家候选与 NewBox 同款，默认 doh.pub；
 * - 不配 bootstrapDns：endpoint 是域名时第一跳仍走系统 DNS 解析它。
 */
data class DohConfig(val enabled: Boolean, val endpoint: String)

object Doh {

    const val DEFAULT_ENDPOINT = "https://doh.pub/dns-query"

    val BUILTIN_ENDPOINTS = listOf(
        "https://doh.pub/dns-query",
        "https://doh.360.cn/dns-query",
        "https://dns.alidns.com/dns-query",
    )

    /** DoH endpoint 校验：须为 https 且带路径；首尾空白忽略。 */
    fun isValidEndpoint(raw: String): Boolean {
        val url = raw.trim().toHttpUrlOrNull() ?: return false
        if (url.scheme != "https") return false
        if (url.host.isBlank()) return false
        val path = url.encodedPath
        if (path.isBlank() || path == "/") return false
        return true
    }

    /** 生效地址：存的值合法就用它（去空白），否则回默认候选。 */
    fun resolveEndpoint(stored: String?): String {
        val trimmed = stored?.trim().orEmpty()
        return if (trimmed.isNotEmpty() && isValidEndpoint(trimmed)) trimmed else DEFAULT_ENDPOINT
    }
}
