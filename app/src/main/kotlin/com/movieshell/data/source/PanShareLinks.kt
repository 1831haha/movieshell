package com.movieshell.data.source

/**
 * v1.1.23：网盘分享链接识别（通用，不认站名）。
 *
 * CMS 详情页里夸克、UC、百度、阿里线路给的都是分享页链接，之前当直链交给
 * 播放器必然黑屏。这里只做识别与拆解，解析动作在 PanDispatch。
 * v1.1.24：阿里、光鸭、迅雷三家也接进来，六家全走规则解析。
 */
object PanShareLinks {

    enum class Platform { QUARK, UC, BAIDU, ALIYUN, GUANGYA, XUNLEI }

    private val QUARK_RE = Regex("""https?://(?:pan|drive)\.quark\.cn/s/([A-Za-z0-9]+)""")
    private val UC_RE = Regex("""https?://drive\.uc\.cn/s/([A-Za-z0-9]+)""")
    private val BAIDU_RE = Regex("""https?://pan\.baidu\.com/s/([A-Za-z0-9_-]+)""")
    private val ALIYUN_RE =
        Regex("""https?://(?:www\.)?(?:aliyundrive\.com|alipan\.com)/(?:s|folder)/([A-Za-z0-9]+)""")
    private val GUANGYA_RE =
        Regex("""https?://(?:www\.)?guangyapan\.com/s/(\d{19}_[A-Za-z0-9_-]+)""")
    private val XUNLEI_RE = Regex("""https?://pan\.xunlei\.com/s/([A-Za-z0-9_-]+)""")

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
            GUANGYA_RE.containsMatchIn(u) -> Platform.GUANGYA
            XUNLEI_RE.containsMatchIn(u) -> Platform.XUNLEI
            else -> null
        }
    }

    /** 拆出分享 id 与提取码（链接 ?pwd= 或 “密码:xxxx” 写法）。 */
    fun parse(url: String): Pair<String, String>? {
        val u = url.trim()
        val m = QUARK_RE.find(u) ?: UC_RE.find(u) ?: BAIDU_RE.find(u) ?: ALIYUN_RE.find(u)
            ?: GUANGYA_RE.find(u) ?: XUNLEI_RE.find(u) ?: return null
        val id = m.groupValues[1]
        val pwd = Regex("""[?&]pwd=([A-Za-z0-9]+)""").find(u)?.groupValues?.get(1).orEmpty()
            .ifEmpty { Regex("""密码[:：]?\s*([A-Za-z0-9]+)""").find(u)?.groupValues?.get(1).orEmpty() }
        return id to pwd
    }
}
