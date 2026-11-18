package com.movieshell.data.sharelink

/**
 * v1.3.5：六盘分享链接直链播放——只做粘贴链接播，不做网盘中心。
 *
 * 识别与模型层（纯函数，可单测）。解析链路按盘分文件：
 * 夸克/UC 走 QuarkUcApi，百度走 BaiduApi，阿里走 AliyunApi，
 * 光鸭走 GuangyaApi，迅雷走 XunleiApi，统一经 ShareResolver 分发。
 * 接口一律以 v1.1.31 砍盘前旧代码为准；旧代码标过未验证的，
 * 各盘文件头如实写明，不许编造能跑的假接口。
 */
enum class SharePlatform(
    val dirName: String,
    val displayName: String,
    val appName: String,
) {
    QUARK("quark", "夸克", "夸克"),
    UC("uc", "UC", "UC"),
    BAIDU("baidu", "百度", "百度"),
    ALIYUN("aliyun", "阿里云盘", "阿里云盘"),
    GUANGYA("guangya", "光鸭", "光鸭"),
    XUNLEI("xunlei", "迅雷", "迅雷"),
}

object ShareLink {

    private val QUARK_RE = Regex("""https?://(?:pan|drive)\.quark\.cn/s/([A-Za-z0-9]+)""")
    private val UC_RE = Regex("""https?://drive\.uc\.cn/s/([A-Za-z0-9]+)""")
    private val BAIDU_RE = Regex("""https?://pan\.baidu\.com/s/([A-Za-z0-9_-]+)""")
    private val ALIYUN_RE =
        Regex("""https?://(?:www\.)?(?:aliyundrive\.com|alipan\.com)/(?:s|folder)/([A-Za-z0-9]+)""")
    private val GUANGYA_RE =
        Regex("""https?://(?:www\.)?guangyapan\.com/s/(\d{19}_[A-Za-z0-9_-]+)""")
    private val XUNLEI_RE = Regex("""https?://pan\.xunlei\.com/s/([A-Za-z0-9_-]+)""")

    /** 视频后缀（与旧六盘规则一致）。 */
    val VIDEO_EXTS = setOf(
        ".mp4", ".mkv", ".mov", ".avi", ".flv", ".wmv",
        ".m4v", ".ts", ".rmvb", ".webm",
    )

    fun isVideoName(name: String): Boolean {
        val lower = name.lowercase()
        return VIDEO_EXTS.any { lower.endsWith(it) }
    }

    /** 是任一网盘分享链接。 */
    fun isShare(url: String): Boolean = platformOf(url) != null

    /** 认出是哪家盘；认不出返回 null。 */
    fun platformOf(url: String): SharePlatform? {
        val u = url.trim()
        return when {
            QUARK_RE.containsMatchIn(u) -> SharePlatform.QUARK
            UC_RE.containsMatchIn(u) -> SharePlatform.UC
            BAIDU_RE.containsMatchIn(u) -> SharePlatform.BAIDU
            ALIYUN_RE.containsMatchIn(u) -> SharePlatform.ALIYUN
            GUANGYA_RE.containsMatchIn(u) -> SharePlatform.GUANGYA
            XUNLEI_RE.containsMatchIn(u) -> SharePlatform.XUNLEI
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

    /** 人话大小：5428969805 → "5.1 GB"。纯函数，可单测。 */
    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val text = if (value >= 100 || unit == 0) value.toLong().toString()
        else String.format("%.1f", value)
        return text + " " + units[unit]
    }

    /** 人话时长：2725 → "45:25"。纯函数，可单测。 */
    fun humanDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "${m}:${s.toString().padStart(2, '0')}"
    }

    /**
     * v1.3.9：集数列表按顺序排——文件夹在前、文件在后，各组内按文件名自然排序
     * （数字段按数值比，"02" < "10"，不是字典序的 "10" < "2"）。服务端原序
     * 是按更新时间倒序，30 集会被打散。纯函数，可单测。
     */
    fun sortShareFiles(files: List<ShareFile>): List<ShareFile> =
        files.sortedWith(
            compareBy<ShareFile> { !it.isDir }
                .thenBy(Comparator<String> { a, b -> compareNatural(a, b) }) { it.name },
        )

    /** 自然排序：数字段按数值比，其余按字典比（忽略大小写）。纯函数，可单测。 */
    fun compareNatural(a: String, b: String): Int {
        val aParts = splitNatural(a)
        val bParts = splitNatural(b)
        for (i in 0 until minOf(aParts.size, bParts.size)) {
            val (aIsDigit, x) = aParts[i]
            val (bIsDigit, y) = bParts[i]
            val cmp = if (aIsDigit && bIsDigit) {
                // 数字段：先比去零后的位数，再比字典——数值比，不怕 Long 溢出。
                val xs = x.trimStart('0')
                val ys = y.trimStart('0')
                when {
                    xs.length != ys.length -> xs.length.compareTo(ys.length)
                    else -> xs.compareTo(ys)
                }
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return aParts.size.compareTo(bParts.size)
    }

    /** 把名字切成数字段/非数字段交替的块。 */
    private fun splitNatural(s: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        var i = 0
        while (i < s.length) {
            val digit = s[i].isDigit()
            var j = i + 1
            while (j < s.length && s[j].isDigit() == digit) j++
            out.add(digit to s.substring(i, j))
            i = j
        }
        return out
    }

    /** 集数小方块上的字：去掉后缀，"01.mp4" → "01"。纯函数，可单测。 */
    fun episodeLabel(name: String): String = name.substringBeforeLast(".", missingDelimiterValue = name)
}

/** 分享里的一项：文件夹或文件。dirKey 是下钻用的键（各盘含义不同，见各盘 Api）。 */
data class ShareFile(
    val fid: String,
    val name: String,
    val size: Long = 0L,
    val isDir: Boolean = false,
    val dirKey: String = "",
    val fidToken: String = "",
    val folderId: String = "",
    val durationSec: Long = 0L,
)

/**
 * 列目录时一起拿到的会话凭证。夸克/UC 的 fidToken 与 stoken 是同一会话配对的，
 * 取直链必须用同一份 stoken（混用新 stoken 会报 41020 token 校验异常，
 * 2026-11-18 沙箱实测），所以 resolve 一律吃这份 session，不许重取。
 */
sealed interface ShareSession {
    data class QuarkUc(val pwdId: String, val stoken: String, val passcode: String) : ShareSession
    data class Baidu(
        val uk: String,
        val shareId: String,
        val seckey: String,
        val full: String,
        val pwd: String,
    ) : ShareSession

    data class Aliyun(
        val shareId: String,
        val pwd: String,
        val token: String,
        val tokenExpiresAtMs: Long,
    ) : ShareSession

    data class Guangya(val shareId: String, val code: String, val accessToken: String) : ShareSession
    data class Xunlei(val shareId: String, val code: String, val passCodeToken: String) : ShareSession
}

/** 一次列目录的结果：标题 + 本目录直属条目（文件夹在前）+ 会话凭证。 */
data class ShareListing(
    val platform: SharePlatform,
    val shareUrl: String,
    val title: String,
    val files: List<ShareFile>,
    val session: ShareSession,
)

/** 取到的播放地址。isPreview=true 时是游客 2 分钟预览，不是全集。 */
data class PlayLink(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isPreview: Boolean = false,
    val note: String = "",
)

/** 解析结果：成功或带人话原因的失败。needLogin=true 时 UI 应引导去登录。 */
sealed interface ShareResult<out T> {
    data class Ok<T>(val value: T) : ShareResult<T>
    data class Err(
        val message: String,
        val needLogin: Boolean = false,
        val dead: Boolean = false,
    ) : ShareResult<Nothing>
}
