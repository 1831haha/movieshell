package com.movieshell.ui.components

/**
 * v0.109.0 B7：站源图标三级 fallback（可复用）。
 *
 * 背景：用户追问"DsPlayer 为什么会有 LOGO"——实测订阅 https://9280.kstore.vip/newwex.json
 * 得出：99 个站源条目只有 api/ext/filterable/indexs/key/name/quickSearch/searchable/type
 * 字段，无专用 logo 字段；但部分站源的 ext 本身就是图片 URL
 * （如 Douban 的 https://cdn.waimaimingtang.com/file/images/bwc/2026041418124）。
 * DsPlayer 的 logo 大概率是内置图标包按 key/name 匹配。
 *
 * 策略：
 * ① ext 若为图片 URL → 直接当 logo 用；
 * ② 内置常见站源图标映射表（BUILTIN_ICONS，按 key/name 匹配；这版表可为空，
 *    图标资源后续攒，框架先行）；
 * ③ 兜底：返回 null，调用方画首字色块 + 主题渐变。
 *
 * ext 误判防范（读 SubSiteStore.expandSite 得出的 ext 用途）：
 * - KIND_DRPY：ext 可能是 .js 规则文件 URL（api 是 drpy2.min.js 引擎时）；
 * - KIND_SPIDER：ext 下载后会被替换成文件内容（JSON 文本）；
 * - KIND_CMS/PY/TYPE4：ext 多为配置串/参数/JSON。
 * 所以 ext 认图片必须同时满足：http(s) 开头、无 JSON 特征、无空白、
 * 路径后缀是图片后缀，或后缀不明但路径含图片目录特征（images/img/logo/avatar）。
 * content-type 的 HEAD 校验默认不做（99 个源全量 HEAD 成本太高），只做纯字符串判断。
 * 注意：Coil 默认解不了 SVG，ext 是 .svg 时也会走到这里，调用方用 AsyncImage
 * 加载失败时回退首字色块即可，不崩。
 */
private val IMAGE_SUFFIXES = setOf("jpg", "jpeg", "png", "webp", "gif", "svg")

/** 无后缀图片 URL 的路径特征（启发式：命中才认，见文件头注释）。 */
private val IMAGE_PATH_HINTS = listOf("images", "img/", "logo", "avatar", "icon")

/**
 * 内置常见站源图标映射表：key 或 name（小写）→ 图标 URL。
 * v0.109.0：表可为空，图标资源后续攒，匹配框架先行。
 * 后续攒到图标时往这里加一行即可，key/name 优先用订阅里的 key。
 */
private val BUILTIN_ICONS: Map<String, String> = mapOf(
    // 示例： "douban" to "https://example.com/icons/douban.png",
)

/** 按 key/name（不区分大小写）查内置图标表。 */
fun builtinSiteIconUrl(key: String, name: String): String? {
    BUILTIN_ICONS[key.lowercase()]?.let { return it }
    val n = name.lowercase()
    if (n.isNotBlank()) BUILTIN_ICONS[n]?.let { return it }
    return null
}

/** ext 是否看起来像图片 URL（纯字符串判断，不做网络请求）。 */
fun extLooksLikeImageUrl(ext: String): Boolean {
    val e = ext.trim()
    if (e.isEmpty()) return false
    val lower = e.lowercase()
    // 必须是 http(s) URL；排除 data URI、本地路径、纯配置串
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    // JSON/配置串特征一律排除（spider 下载后的 ext 就是文件内容）
    if (e.contains("{") || e.contains("}") || e.contains(" ")) return false
    // 去掉 query/fragment，按 path 判断
    val path = lower.substringBefore("?").substringBefore("#")
    if (path.isBlank()) return false
    val lastSeg = path.substringAfterLast("/")
    val suffix = lastSeg.substringAfterLast(".", "")
    // ① 后缀明确是图片 → 认
    if (suffix in IMAGE_SUFFIXES && suffix != lastSeg) return true
    // ② 后缀不明（如 Douban 的 /file/images/bwc/2026041418124）：
    //     只有路径含图片目录特征才认，避免 CMS 配置串误判
    if (suffix == lastSeg || suffix.isBlank()) {
        return IMAGE_PATH_HINTS.any { hint -> path.contains(hint) }
    }
    // ③ 有后缀但不是图片（.js/.py/.json/.txt/.m3u8…）→ 不认
    return false
}

/**
 * 从 http(s) URL 里取 host（去端口、去路径/参数），非 URL 返回 null。
 * 供订阅站源（api 是接口 URL）与本地规则（文件头里的 host/首个 URL）取站点域用。
 */
fun hostFromUrl(url: String): String? {
    val u = url.trim()
    if (!u.startsWith("http://") && !u.startsWith("https://")) return null
    val host = u.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
    return host.ifBlank { null }
}

/**
 * v0.140.0：站点 favicon（图标第四级）。
 * 直接取站点根目录的 favicon.ico，不走第三方图标服务（避免无图标站点被
 * 通用占位图盖住首字）。Coil 解不了真 ICO 格式时加载失败，调用方
 * 的首字底层自然露出，不崩。
 */
fun faviconUrlForHost(host: String): String? {
    val h = host.trim().lowercase().substringBefore(":").substringBefore("/").substringBefore(" ")
    if (h.isEmpty()) return null
    return "https://$h/favicon.ico"
}

/**
 * 站源 logo 三级 fallback：
 * ① ext 是图片 URL → 直接用；
 * ② 内置图标表按 key/name 匹配；
 * ③ 返回 null，调用方兜底画首字色块 + 主题渐变。
 * v0.140.0 补充④（调用方做，不进本函数）：站点 host 的 favicon.ico。
 */
fun siteLogoUrl(key: String, name: String, ext: String?): String? {
    if (!ext.isNullOrBlank() && extLooksLikeImageUrl(ext)) return ext.trim()
    builtinSiteIconUrl(key, name)?.let { return it }
    return null
}
