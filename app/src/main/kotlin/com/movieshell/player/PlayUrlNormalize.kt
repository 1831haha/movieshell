package com.movieshell.player

/**
 * v0.294.2：规则/接口返回的播放地址剥掉误带的 TVBox「集名$」前缀。
 *
 * TVBox 约定 vod_play_url 是 `集名$地址`（# 分集、$$$ 分线路）。部分 PY/drpy 规则把
 * 选集 id 编成 `线路@序号@集名$地址`（如 `暴风@0@HD$https://…/index.m3u8`），playerContent
 * 只剥了 `线路@序号@`，返回的 url 仍是 `HD$https://…`，起播校验因此拒绝
 * （日志「播放地址非法，已拒绝：HD$https://…」「正片$https://…」）。
 *
 * 规则：本身已是可识别地址（scheme:// / magnet: / 绝对路径）→ 原样返回（地址里自带 `$`
 * 的直链不动）；否则含 `$` 且最后一个 `$` 之后是可识别地址 → 取 `$` 之后的部分；
 * 其余原样返回，交给 [EngineHost.isPlayableUrl] 照旧判定，校验本身不放宽。
 */
private val SCHEME_URL = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

internal fun looksLikePlayAddress(s: String): Boolean {
    val u = s.trim()
    return SCHEME_URL.containsMatchIn(u) || u.startsWith("magnet:", ignoreCase = true) || u.startsWith("/")
}

fun normalizeResolvedPlayUrl(raw: String): String {
    val u = raw.trim()
    if (u.isEmpty() || looksLikePlayAddress(u)) return u
    val idx = u.lastIndexOf('$')
    if (idx < 0 || idx == u.length - 1) return u
    // 名字段里不该有地址：前缀含 :// 说明不是「集名$」，不动
    val prefix = u.substring(0, idx)
    if (prefix.contains("://")) return u
    val tail = u.substring(idx + 1).trim()
    return if (looksLikePlayAddress(tail)) tail else u
}
