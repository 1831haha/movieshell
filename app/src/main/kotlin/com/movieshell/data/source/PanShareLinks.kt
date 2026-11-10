package com.movieshell.data.source

/**
 * v1.1.23：网盘分享链接识别（通用，不认站名）。
 *
 * CMS 详情页里夸克、UC、百度、阿里线路给的都是分享页链接，之前当直链交给
 * 播放器必然黑屏。这里只做识别与拆解，解析动作在 PanDispatch。
 * 先只接夸克，其余三家留口子（platformOf 能认出，dispatch 再拦）。
 */
object PanShareLinks {

    enum class Platform { QUARK, UC, BAIDU, ALIYUN }

    private val QUARK_RE = Regex("""https?://(?:pan|drive)\.quark\.cn/s/([A-Za-z0-9]+)""")
    private val UC_RE = Regex("""https?://drive\.uc\.cn/s/([A-Za-z0-9]+)""")
    private val BAIDU_RE = Regex("""https?://pan\.baidu\.com/s/([A-Za-z0-9_-]+)""")
    private val ALIYUN_RE = Regex("""https?://(?:www\.)?aliyundrive\.com/s/([A-Za-z0-9]+)""")

    /** 是任一网盘分享链接。 */
    fun isShare(url: String): Boolean = platformOf(url) != null

    /** 认出是哪家盘；认不出返回 null。 */
    fun platformOf(url: String): Platform? {
        val u = url.trim()
        return when {
            QUARK_RE.containsMatchIn(u) -> Platform.QUARK
            UC_RE.containsMatchIn(u) -> Platform.UC
            BAIDU_RE.containsMatchIn(u) -> Platform.BAIDU
            ALIYUN_RE.containsMatchIn(u) -> Platform.ALIYUN
            else -> null
        }
    }

    /** 拆出分享 id 与提取码（链接 ?pwd= 或 “密码:xxxx” 写法）。 */
    fun parse(url: String): Pair<String, String>? {
        val u = url.trim()
        val m = QUARK_RE.find(u) ?: UC_RE.find(u) ?: BAIDU_RE.find(u) ?: ALIYUN_RE.find(u)
            ?: return null
        val id = m.groupValues[1]
        val pwd = Regex("""[?&]pwd=([A-Za-z0-9]+)""").find(u)?.groupValues?.get(1).orEmpty()
            .ifEmpty { Regex("""密码[:：]?\s*([A-Za-z0-9]+)""").find(u)?.groupValues?.get(1).orEmpty() }
        return id to pwd
    }
}
