package com.movieshell.data.source

import com.movieshell.player.MediaTypeSniffer

/**
 * v0.294.7：规则 playerContent / lazy 的统一原始结果（所有引擎都转成它）。
 * - parse：1=需要解析/嗅探；jx：1=走解析线路（jx 接口）；playUrl：TVBox 解析前缀
 *   （`json:接口`、`parse:线路名`、或直接是解析页地址前缀）。
 */
data class RawPlay(
    val url: String = "",
    val parse: Int = 0,
    val jx: Int = 0,
    val header: Map<String, String> = emptyMap(),
    val playUrl: String = "",
)

/** 播放路由决策结果。 */
sealed class PlayRoute {
    abstract val url: String

    /** 直接播 */
    data class Direct(override val url: String) : PlayRoute()

    /** 走解析线路（jx 列表，并发） */
    data class JxList(override val url: String) : PlayRoute()

    /** 嗅探该网页本身（TVBox parse=1 & jx=0 的语义） */
    data class SniffPage(override val url: String) : PlayRoute()

    /** playUrl = `json:接口`：用该 JSON 接口解析 */
    data class JsonPrefix(val api: String, override val url: String) : PlayRoute()

    /** playUrl = `parse:线路名`：用指定名字的解析线路 */
    data class NamedParse(val name: String, override val url: String) : PlayRoute()

    /** playUrl = 解析页前缀（http…）：拼接后嗅探 */
    data class PrefixSniff(val prefix: String, override val url: String) : PlayRoute()
}

/**
 * v0.294.7（Bug1 / H1 / H7 / M1）：播放路由（纯函数，可单测）。
 *
 * 规则（对齐 TVBox / FongMi 口径）：
 * 1. 地址本身已是媒体直链（且未声明 jx=1）→ 直接播，parse 标记忽略；
 * 2. 有 playUrl 前缀（规则给的优先，其次站点级）→ 按前缀类型；
 * 3. jx=1 → 解析线路；
 * 4. parse=1：VIP flag / VIP 站点地址 → 解析线路，否则嗅探该网页本身；
 * 5. parse=0：VIP 站点地址 → 解析线路；明显是网页（.html 等）→ VIP flag 走解析、否则嗅探网页（H7）；
 *    其余直接播（旧行为，无后缀真流不白等解析）。
 * 执行侧对每种路由都有回退（见 PlayResolver），新路由失败时回到旧路径。
 */
object PlayRouting {

    /** 订阅没给 flags 时的默认 VIP 标识（TVBox 默认表 + 常见别名） */
    val DEFAULT_VIP_FLAGS = listOf(
        "qq", "qiyi", "iqiyi", "youku", "mgtv", "imgo", "letv", "leshi", "sohu", "tudou",
        "pptv", "bilibili", "wasu", "m1905", "1905", "migu", "xigua", "renrenmi", "ltnb",
    )

    private val VIP_HOSTS = listOf(
        "v.qq.com", "iqiyi.com", "iq.com", "youku.com", "mgtv.com", "bilibili.com", "le.com",
        "sohu.com", "pptv.com", "1905.com", "tudou.com", "wasu.cn", "miguvideo.com", "ixigua.com",
    )

    private val PAGE_EXTS = listOf(".html", ".htm", ".shtml", ".jsp", ".asp", ".aspx", ".do")

    fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringBefore(':').lowercase()

    fun isVipUrl(url: String): Boolean {
        val h = hostOf(url)
        if (h.isEmpty()) return false
        return VIP_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    fun isVipFlag(flag: String, vipFlags: Collection<String>): Boolean {
        val f = flag.trim().lowercase()
        if (f.isEmpty()) return false
        val list = vipFlags.takeIf { it.isNotEmpty() } ?: DEFAULT_VIP_FLAGS
        return list.any { it.trim().lowercase() == f }
    }

    /** 地址看起来是可直接交给播放器的媒体（后缀 / /m3u8 路径 / rtmp / 磁力 / 本地文件）。 */
    fun isMediaUrl(url: String): Boolean {
        val u = url.trim()
        val lower = u.lowercase()
        if (lower.startsWith("magnet:") || lower.startsWith("rtmp") || lower.startsWith("rtsp://") ||
            lower.startsWith("rtp://") || lower.startsWith("udp://") || lower.startsWith("file://") ||
            lower.startsWith("content://") || lower.startsWith("/")
        ) return true
        if (MediaTypeSniffer.inferFromUrl(u) != null) return true
        return runCatching { SniffEngine.isPlayableDirectUrl(u) }.getOrDefault(false)
    }

    /** H7：明显是网页（不是流）——.html 等后缀，或 VIP 站播放页。 */
    fun looksLikeWebPage(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (isMediaUrl(url)) return false
        if (isVipUrl(url)) return true
        val path = lower.substringBefore('#').substringBefore('?')
        return PAGE_EXTS.any { path.endsWith(it) }
    }

    /** playUrl 前缀 → 路由；认不出的前缀返回 null（忽略前缀）。 */
    fun parsePrefix(prefix: String, url: String): PlayRoute? {
        val p = prefix.trim()
        if (p.isEmpty()) return null
        return when {
            p.startsWith("json:", ignoreCase = true) ->
                p.substring(5).trim().takeIf { it.startsWith("http", ignoreCase = true) }
                    ?.let { PlayRoute.JsonPrefix(it, url) }
            p.startsWith("parse:", ignoreCase = true) ->
                p.substring(6).trim().takeIf { it.isNotEmpty() }?.let { PlayRoute.NamedParse(it, url) }
            p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true) ->
                PlayRoute.PrefixSniff(p, url)
            else -> null
        }
    }

    fun decide(
        raw: RawPlay,
        flag: String,
        vipFlags: Collection<String> = emptyList(),
        sitePlayUrl: String = "",
    ): PlayRoute {
        val url = raw.url.trim()
        if (url.isEmpty()) return PlayRoute.Direct("")
        if (raw.jx != 1 && isMediaUrl(url)) return PlayRoute.Direct(url)
        val prefix = raw.playUrl.trim().ifEmpty { sitePlayUrl.trim() }
        parsePrefix(prefix, url)?.let { return it }
        if (raw.jx == 1) return PlayRoute.JxList(url)
        val vip = isVipFlag(flag, vipFlags) || isVipUrl(url)
        if (raw.parse == 1) {
            return if (vip) PlayRoute.JxList(url) else PlayRoute.SniffPage(url)
        }
        // parse=0：保守——只有明显是网页时才改路由（无后缀的真流仍直接播，不白等解析）
        if (isVipUrl(url)) return PlayRoute.JxList(url)
        if (looksLikeWebPage(url)) {
            return if (vip) PlayRoute.JxList(url) else PlayRoute.SniffPage(url)
        }
        return PlayRoute.Direct(url)
    }

    /**
     * 解析线路排序（M1 / M4，纯函数）：声明了 flag 且命中当前 flag 的最先；未声明 flag 的其次；
     * 声明了 flag 但不含当前 flag 的最后。同档按 [score]（成功率）降序，再按原顺序。
     */
    fun <T> orderLines(
        lines: List<T>,
        flag: String,
        flagsOf: (T) -> List<String>,
        score: (T) -> Double = { 0.0 },
    ): List<T> {
        val f = flag.trim().lowercase()
        fun tier(t: T): Int {
            val fl = flagsOf(t)
            if (fl.isEmpty() || f.isEmpty()) return 1
            return if (fl.any { it.trim().lowercase() == f }) 0 else 2
        }
        return lines.withIndex()
            .sortedWith(compareBy<IndexedValue<T>>({ tier(it.value) }, { -score(it.value) }, { it.index }))
            .map { it.value }
    }
}
