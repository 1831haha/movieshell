package com.movieshell.data.source

import com.movieshell.core.log.LogBus
import com.movieshell.core.net.HttpClient
import com.movieshell.core.net.ProxyRouting
import com.movieshell.core.net.ioSafe
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/**
 * v1.1.31：Emby / Jellyfin 媒体服务器客户端。
 *
 * 两家是同一套 API 血统（Jellyfin 从 Emby 分叉），登录、浏览、搜索、直链的接口形状一致，
 * 唯一区别是 Emby 的接口根在 /emby 之下、Jellyfin 在 / 之下（见 [apiBase]）。
 *
 * 只做：账号登录（用户名密码 / API Key）、媒体库浏览、搜索、继续观看、原画直链播放地址。
 * 不做：转码（只拉 Static=true 原画直链，播不了的格式走播放器双内核兜底）、进度上报、
 * 音乐/图片。进度续播走本机 HistoryStore（播前按服务器的 PlaybackPositionTicks 预置）。
 */
object EmbyApi {

    private const val TAG = "EmbyApi"
    private const val CLIENT_NAME = "XiaYing"
    private const val CLIENT_VERSION = "1.2.4"

    private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    data class EmbyUser(
        val Id: String = "",
        val Name: String = "",
    )

    @Serializable
    data class EmbyAuthResult(
        val AccessToken: String = "",
        val User: EmbyUser? = null,
        val ServerId: String = "",
    )

    @Serializable
    data class EmbyUserData(
        val Played: Boolean = false,
        val PlaybackPositionTicks: Long = 0L,
        val PlayedPercentage: Double? = null,
    )

    /** Emby / Jellyfin 章节（片头片尾跳过用；只有取详情时 Fields 带 Chapters 才回）。 */
    @Serializable
    data class EmbyChapter(
        val Name: String = "",
        val StartPositionTicks: Long = 0L,
    )

    /** 片头 / 片尾跳过目标：按钮文案 + 点按后 seek 到的毫秒位置。 */
    data class SkipTarget(
        val label: String,
        val seekToMs: Long,
    )

    @Serializable
    data class EmbyItem(
        val Id: String = "",
        val Name: String = "",
        val Type: String = "",
        val CollectionType: String? = null,
        val Overview: String = "",
        val ProductionYear: Int? = null,
        val RunTimeTicks: Long = 0L,
        val ChildCount: Int = 0,
        val IndexNumber: Int? = null,
        val ParentIndexNumber: Int? = null,
        val SeriesId: String? = null,
        val SeriesName: String? = null,
        val SeasonId: String? = null,
        val UserData: EmbyUserData? = null,
        val Chapters: List<EmbyChapter> = emptyList(),
    )

    @Serializable
    data class EmbyItemsResponse(
        val Items: List<EmbyItem> = emptyList(),
        val TotalRecordCount: Int = 0,
    )

    /** 服务器类型：emby 接口根带 /emby 前缀，jellyfin 不带。 */
    enum class ServerType { EMBY, JELLYFIN }

    data class Auth(
        val token: String,
        val userId: String,
    )

    // ---------------- 纯函数（可单测） ----------------

    /** 地址归一化：去空白/尾斜杠/尾部 /emby（类型前缀由 [apiBase] 再补，不由用户地址带）。 */
    fun normalizeBase(raw: String): String {
        var u = raw.trim().removeSuffix("/")
        // v1.3.6：自愈重复拼上的协议——旧三格表单曾把带协议的主机再拼一遍协议，
        // 形如 http://https//host、https://https://host；以最后一段有效协议为准。
        // 注意主机本身可能以 http 开头（如 httpbin.org），协议后必须跟 : 或 / 才算协议。
        var scheme = ""
        var rest = u
        while (true) {
            val m = Regex("^(https?)[:/]+", RegexOption.IGNORE_CASE).find(rest) ?: break
            scheme = m.groupValues[1].lowercase()
            rest = rest.substring(m.range.last + 1)
        }
        u = if (scheme.isEmpty()) {
            // v1.1.31：没写协议头的裸地址（如 192.168.1.10:8096）默认补 http://，
            // 否则 OkHttp 建请求直接炸，登录页只剩一句"登录失败"
            "http://$rest"
        } else {
            "$scheme://$rest"
        }
        if (u.endsWith("/emby", ignoreCase = true)) u = u.dropLast(5).removeSuffix("/")
        return u
    }

    /** 接口根：Emby 在 /emby 之下，Jellyfin 在 / 之下。 */
    fun apiBase(baseUrl: String, type: ServerType): String =
        normalizeBase(baseUrl) + if (type == ServerType.EMBY) "/emby" else ""

    /**
     * 拆分后的服务器地址（v1.2.14：表单主机 / 路径 / 端口三格用，纯函数，可单测）。
     * port 为空表示地址里没写端口；path 为空表示无反代路径；尾部 /emby 由
     * [normalizeBase] 剥掉（类型前缀由 [apiBase] 再补，不由用户地址带）。
     */
    data class SplitAddress(
        val scheme: String,
        val host: String,
        val port: String,
        val path: String,
    )

    /** 整 URL 拆回四格：先走 [normalizeBase]（补协议、去尾斜杠与 /emby 后缀）再拆。 */
    fun splitBaseUrl(raw: String): SplitAddress {
        val u = normalizeBase(raw)
        val scheme = u.substringBefore("://", "http")
        val rest = u.substringAfter("://", "")
        val authority = rest.substringBefore("/")
        val pathRaw = rest.substringAfter("/", "").removeSuffix("/")
        val path = if (pathRaw.isBlank()) "" else "/$pathRaw"
        val (host, port) = if (authority.startsWith("[")) {
            val end = authority.indexOf("]")
            if (end < 0) {
                authority to ""
            } else {
                val h = authority.substring(0, end + 1)
                val p = authority.substring(end + 1).removePrefix(":")
                h to if (p.all { it.isDigit() }) p else ""
            }
        } else if (":" in authority && authority.substringAfterLast(":").all { it.isDigit() }) {
            authority.substringBeforeLast(":") to authority.substringAfterLast(":")
        } else {
            authority to ""
        }
        return SplitAddress(scheme, host, port, path)
    }

    /**
     * 四格拼回整 URL（纯函数，可单测）：端口只认数字、路径自动补前导斜杠去尾斜杠。
     * 拼完由调用方再过一次 [normalizeBase]（与旧的整 URL 保存口径一致）。
     */
    fun joinAddress(scheme: String, host: String, port: String, path: String): String {
        val s = scheme.trim().ifBlank { "http" }
        val h = host.trim()
        if (h.isBlank()) return ""
        val p = port.trim().filter { it.isDigit() }
        val pathRaw = path.trim().removeSuffix("/")
        val pa = when {
            pathRaw.isBlank() -> ""
            pathRaw.startsWith("/") -> pathRaw
            else -> "/$pathRaw"
        }
        return "$s://$h" + (if (p.isBlank()) "" else ":$p") + pa
    }

    /**
     * v1.3.6：单地址框的解析结果。整段粘贴什么都认：
     * 带协议照用、没协议不写死（由 [candidateBases] 先 https 后 http 试），
     * 路径里纯数字（如 /443）当端口、尾部 /emby 剥掉（类型前缀由 [apiBase] 再补）。
     */
    data class ParsedAddress(
        val scheme: String,
        val host: String,
        val port: String,
        val path: String,
        val hasScheme: Boolean,
    )

    /** 单地址框解析（纯函数，可单测）：复用 [splitBaseUrl] 的拆法，再补三处容错。 */
    fun parseAddress(raw: String): ParsedAddress {
        val input = raw.trim()
        // 先判有没有写协议：协议段只认行首的 http/https，后跟 : 或 /（主机本身
        // 以 http 开头如 httpbin.org 不算协议）。
        val hasScheme = Regex("^(https?)[:/]+", RegexOption.IGNORE_CASE).containsMatchIn(input)
        val split = splitBaseUrl(input)
        var port = split.port
        var path = split.path
        // 路径是纯数字时当端口——用户常把 443 填进路径框。
        if (port.isBlank() && path.length > 1 && path.drop(1).all { it.isDigit() }) {
            port = path.drop(1)
            path = ""
        }
        // 没写协议时 scheme 留空，不冒充 http——候选表由 hasScheme 决定先 https 后 http。
        return ParsedAddress(if (hasScheme) split.scheme else "", split.host, port, path, hasScheme)
    }

    /** 拼回整 URL：默认端口（https 443 / http 80）去掉，拼完与手写短地址一致。 */
    private fun joinParsed(scheme: String, host: String, port: String, path: String): String {
        val defaultPort = (scheme == "https" && port == "443") || (scheme == "http" && port == "80")
        return joinAddress(scheme, host, if (defaultPort) "" else port, path)
    }

    /**
     * 登录用的候选地址表（纯函数，可单测）：写了协议只试该协议；没写协议先
     * https 后 http（公网服多为 https，局域网再回落 http）。空地址返回空表。
     */
    fun candidateBases(raw: String): List<String> {
        val parsed = parseAddress(raw)
        if (parsed.host.isBlank()) return emptyList()
        return if (parsed.hasScheme) {
            listOf(joinParsed(parsed.scheme, parsed.host, parsed.port, parsed.path))
        } else {
            listOf(
                joinParsed("https", parsed.host, parsed.port, parsed.path),
                joinParsed("http", parsed.host, parsed.port, parsed.path),
            )
        }
    }

    /** Emby/Jellyfin 用 100 纳秒 tick 计时长：1ms = 10_000 tick。 */
    fun ticksToMs(ticks: Long): Long = (ticks / 10_000L).coerceAtLeast(0L)

    /**
     * 章节跳过判定（纯函数，可单测）。
     *
     * 当前进度落在某个片头 / 片尾章节内时，返回跳过目标（按钮文案 + 该章节结尾）；
     * 否则返回 null（调用方不显示按钮，一切照旧）。
     * 章节结尾 = 下一章开头；最后一章的结尾 = 片长（片长未知时不跳）。
     * 章节名忽略大小写匹配：片头 Intro / Introduction / Opening / 片头 / OP；
     * 片尾 Ending / End Credits / Outro / Credits / 片尾 / ED。
     */
    fun findSkipTarget(
        chapters: List<EmbyChapter>,
        positionMs: Long,
        durationMs: Long,
    ): SkipTarget? {
        if (chapters.isEmpty() || positionMs < 0L) return null
        val sorted = chapters.sortedBy { it.StartPositionTicks }
        for ((index, chapter) in sorted.withIndex()) {
            val startMs = ticksToMs(chapter.StartPositionTicks)
            val endMs = if (index + 1 < sorted.size) {
                ticksToMs(sorted[index + 1].StartPositionTicks)
            } else {
                durationMs
            }
            if (positionMs < startMs || positionMs >= endMs) continue
            val name = chapter.Name.lowercase()
            return when {
                name == "op" || "intro" in name || "introduction" in name ||
                    "opening" in name || "片头" in name -> SkipTarget("跳过片头", endMs)
                name == "ed" || "outro" in name || "ending" in name ||
                    "credits" in name || "片尾" in name -> SkipTarget("跳过片尾", endMs)
                else -> null
            }
        }
        return null
    }

    /** 原画直链：Static=true 不转码，api_key 走 query（播放器直连用，不经过 App 中转）。 */
    fun streamUrl(baseUrl: String, type: ServerType, token: String, itemId: String): String {
        val url = "${apiBase(baseUrl, type)}/Videos/$itemId/stream".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("Static", "true")
            ?.addQueryParameter("api_key", token)
            ?.build() ?: return ""
        return url.toString()
    }

    /** 海报地址（Primary 图，限宽防大图）。 */
    fun imageUrl(baseUrl: String, type: ServerType, token: String, itemId: String, maxWidth: Int = 400): String {
        val url = "${apiBase(baseUrl, type)}/Items/$itemId/Images/Primary".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("api_key", token)
            ?.addQueryParameter("maxWidth", maxWidth.toString())
            ?.build() ?: return ""
        return url.toString()
    }

    // ---------------- 联网 ----------------

    private fun authHeader(deviceId: String, token: String? = null): String =
        "MediaBrowser Client=\"$CLIENT_NAME\", Device=\"Android\", DeviceId=\"$deviceId\", " +
            "Version=\"$CLIENT_VERSION\"" + if (token != null) ", Token=\"$token\"" else ""

    private fun client(ignoreCert: Boolean = false): OkHttpClient =
        if (ignoreCert) insecureClient else HttpClient.defaultClient()

    /**
     * v1.2.14：信任放行的 SSL 构造（信任全部证书 + 不校验主机名），登录/浏览
     * 与播放两路共用同一份逻辑；放行范围只限调用方显式选了 ignoreCert 的
     * 那台服务器，全局默认 client 不动。
     */
    private fun insecureSsl(): Pair<SSLSocketFactory, X509TrustManager> {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return sslContext.socketFactory to trustManager
    }

    /**
     * v1.2.14：忽略证书验证的专属 client——只供某台服务器开了 ignoreCert 时走，
     * 全局 HttpClient 不动，其它服务器与全 App 请求仍走默认校验。
     * 信任放行只认这一台服务器的请求：调用方每次按该服务器的 ignoreCert 选
     * client，不存在“开一次全局放行”的路径。自签 / 过期证书的自建服务器可用，
     * 公网服务器不要开（中间人可冒充）。超时与默认 client 同口径（15/15/15/60 秒）。
     * 播放走同口径的 [insecurePlaybackClient]，不共用 callTimeout。
     */
    private val insecureClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val (socketFactory, trustManager) = insecureSsl()
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * v1.2.14：播放专用的放行证书 client——与登录/浏览的 insecureClient
     * 同一套信任放行，只是不设 callTimeout：OkHttp 的 callTimeout 覆盖整条
     * 流的读取过程，设了会把长视频在 60 秒处掐断；连接/读取/写入超时与
     * 默认 client 同口径（15 秒）。只供 PlayerManager 在 ignoreCert 播放时
     * 经 OkHttpDataSource 用，作用域限本次播放的 factory。
     */
    val insecurePlaybackClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val (socketFactory, trustManager) = insecureSsl()
        OkHttpClient.Builder()
            .proxySelector(ProxyRouting.selector)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private suspend fun getText(url: String, deviceId: String, token: String?, ignoreCert: Boolean = false): String? {
        val result = getTextResult(url, deviceId, token, ignoreCert)
        return if (result.code != null && result.code in 200..299) result.body else null
    }

    /** HTTP 结果（v1.3.6）：code 为空表示请求根本没发出去（连不上/超时/SSL 错）。 */
    private data class HttpResult(val code: Int?, val body: String?)

    private suspend fun getTextResult(
        url: String, deviceId: String, token: String?, ignoreCert: Boolean = false,
    ): HttpResult {
        coroutineContext.ensureActive()
        val req = Request.Builder().url(url)
            .header("X-Emby-Authorization", authHeader(deviceId, token))
            .apply { if (token != null) header("X-Emby-Token", token) }
            .build()
        return try {
            client(ignoreCert).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBus.w(TAG, "GET ${resp.code} $url")
                    HttpResult(resp.code, null)
                } else {
                    HttpResult(resp.code, resp.body?.string())
                }
            }
        } catch (e: IOException) {
            LogBus.w(TAG, "GET fail $url: ${e.message}")
            HttpResult(null, null)
        }
    }

    private suspend fun postJson(
        url: String, deviceId: String, body: String, token: String? = null, ignoreCert: Boolean = false,
    ): String? {
        val result = postJsonResult(url, deviceId, body, token, ignoreCert)
        return if (result.code != null && result.code in 200..299) result.body else null
    }

    private suspend fun postJsonResult(
        url: String, deviceId: String, body: String, token: String? = null, ignoreCert: Boolean = false,
    ): HttpResult {
        coroutineContext.ensureActive()
        val req = Request.Builder().url(url)
            .header("X-Emby-Authorization", authHeader(deviceId, token))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client(ignoreCert).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBus.w(TAG, "POST ${resp.code} $url")
                    HttpResult(resp.code, null)
                } else {
                    HttpResult(resp.code, resp.body?.string())
                }
            }
        } catch (e: IOException) {
            LogBus.w(TAG, "POST fail $url: ${e.message}")
            HttpResult(null, null)
        }
    }

    /** 登录失败分原因（v1.3.6）：连不上与账号错走不同提示，调用方不再只报一句登录失败。 */
    enum class LoginFailure { Unreachable, Unauthorized, BadResponse }

    /** 登录结果：成功带 Auth，失败带原因。 */
    sealed interface LoginOutcome {
        data class Success(val auth: Auth) : LoginOutcome
        data class Failed(val failure: LoginFailure) : LoginOutcome
    }

    /** 用户名密码登录 → token + userId。失败返回 null（兼容薄壳，内部调带原因的新函数）。 */
    suspend fun loginByPassword(
        baseUrl: String, type: ServerType, deviceId: String, username: String, password: String,
        ignoreCert: Boolean = false,
    ): Auth? = when (val outcome = loginByPasswordResult(baseUrl, type, deviceId, username, password, ignoreCert)) {
        is LoginOutcome.Success -> outcome.auth
        is LoginOutcome.Failed -> null
    }

    /**
     * 用户名密码登录（带失败原因，v1.3.6）：400/401 是账号错；请求发不出去
     * 与 502 等非 2xx 是连不上；200 但解不出 token 是服务器回的不是 Emby。
     */
    suspend fun loginByPasswordResult(
        baseUrl: String, type: ServerType, deviceId: String, username: String, password: String,
        ignoreCert: Boolean = false,
    ): LoginOutcome = ioSafe(TAG, LoginOutcome.Failed(LoginFailure.Unreachable)) {
        val url = "${apiBase(baseUrl, type)}/Users/AuthenticateByName"
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString()
        val result = postJsonResult(url, deviceId, body, ignoreCert = ignoreCert)
        val code = result.code ?: return@ioSafe LoginOutcome.Failed(LoginFailure.Unreachable)
        if (code == 400 || code == 401) return@ioSafe LoginOutcome.Failed(LoginFailure.Unauthorized)
        if (code !in 200..299) return@ioSafe LoginOutcome.Failed(LoginFailure.Unreachable)
        val text = result.body ?: return@ioSafe LoginOutcome.Failed(LoginFailure.BadResponse)
        val parsed = runCatching { jsonLenient.decodeFromString<EmbyAuthResult>(text) }.getOrNull()
            ?: return@ioSafe LoginOutcome.Failed(LoginFailure.BadResponse)
        val token = parsed.AccessToken
        val userId = parsed.User?.Id.orEmpty()
        if (token.isBlank() || userId.isBlank()) {
            LoginOutcome.Failed(LoginFailure.BadResponse)
        } else {
            LoginOutcome.Success(Auth(token, userId))
        }
    }

    /** API Key 登录：Key 本身当 token，再用 /Users/Me 换 userId。 */
    suspend fun loginByApiKey(
        baseUrl: String, type: ServerType, deviceId: String, apiKey: String, ignoreCert: Boolean = false,
    ): Auth? = when (val outcome = loginByApiKeyResult(baseUrl, type, deviceId, apiKey, ignoreCert)) {
        is LoginOutcome.Success -> outcome.auth
        is LoginOutcome.Failed -> null
    }

    /** API Key 登录（带失败原因，v1.3.6）：与 [loginByPasswordResult] 同一套分原因口径。 */
    suspend fun loginByApiKeyResult(
        baseUrl: String, type: ServerType, deviceId: String, apiKey: String, ignoreCert: Boolean = false,
    ): LoginOutcome = ioSafe(TAG, LoginOutcome.Failed(LoginFailure.Unreachable)) {
        val url = "${apiBase(baseUrl, type)}/Users/Me"
        val result = getTextResult(url, deviceId, apiKey, ignoreCert = ignoreCert)
        val code = result.code ?: return@ioSafe LoginOutcome.Failed(LoginFailure.Unreachable)
        if (code == 400 || code == 401) return@ioSafe LoginOutcome.Failed(LoginFailure.Unauthorized)
        if (code !in 200..299) return@ioSafe LoginOutcome.Failed(LoginFailure.Unreachable)
        val text = result.body ?: return@ioSafe LoginOutcome.Failed(LoginFailure.BadResponse)
        val user = runCatching { jsonLenient.decodeFromString<EmbyUser>(text) }.getOrNull()
            ?: return@ioSafe LoginOutcome.Failed(LoginFailure.BadResponse)
        if (user.Id.isBlank()) {
            LoginOutcome.Failed(LoginFailure.BadResponse)
        } else {
            LoginOutcome.Success(Auth(apiKey, user.Id))
        }
    }

    /** token 是否还有效（GET /Users/{id} 200 即有效）。 */
    suspend fun testAuth(
        baseUrl: String, type: ServerType, deviceId: String, auth: Auth, ignoreCert: Boolean = false,
    ): Boolean =
        ioSafe(TAG, false) {
            getText(
                "${apiBase(baseUrl, type)}/Users/${auth.userId}", deviceId, auth.token,
                ignoreCert = ignoreCert,
            ) != null
        }

    /** 媒体库（电影库/剧集库等顶层视图）。 */
    suspend fun views(
        baseUrl: String, type: ServerType, deviceId: String, auth: Auth, ignoreCert: Boolean = false,
    ): List<EmbyItem> =
        ioSafe(TAG, emptyList()) {
            val url = "${apiBase(baseUrl, type)}/Users/${auth.userId}/Views"
            val text = getText(url, deviceId, auth.token, ignoreCert = ignoreCert) ?: return@ioSafe emptyList()
            jsonLenient.decodeFromString<EmbyItemsResponse>(text).Items
        }

    /**
     * 列表：parentId 为空=全库；types 如 Movie,Series,Episode；search 非空=按词搜索；
     * resuming=true 只取有断点的（继续观看）。
     */
    suspend fun items(
        baseUrl: String, type: ServerType, deviceId: String, auth: Auth,
        parentId: String? = null, types: String = "Movie,Series,Episode",
        search: String? = null, resuming: Boolean = false, limit: Int = 200,
        ignoreCert: Boolean = false,
    ): List<EmbyItem> = ioSafe(TAG, emptyList()) {
        val builder = "${apiBase(baseUrl, type)}/Users/${auth.userId}/Items".toHttpUrlOrNull()?.newBuilder()
            ?: return@ioSafe emptyList()
        builder.addQueryParameter("Recursive", "true")
            .addQueryParameter("IncludeItemTypes", types)
            .addQueryParameter("Fields", "Overview,ProductionYear,RunTimeTicks,UserData,Genres,ChildCount")
            .addQueryParameter("SortBy", "SortName")
            .addQueryParameter("Limit", limit.toString())
        if (!parentId.isNullOrBlank()) builder.addQueryParameter("ParentId", parentId)
        if (!search.isNullOrBlank()) builder.addQueryParameter("SearchTerm", search)
        if (resuming) builder.addQueryParameter("Filters", "IsResuming")
        val text = getText(builder.build().toString(), deviceId, auth.token, ignoreCert = ignoreCert)
            ?: return@ioSafe emptyList()
        jsonLenient.decodeFromString<EmbyItemsResponse>(text).Items
    }

    /** 某剧的季列表。 */
    suspend fun seasons(
        baseUrl: String, type: ServerType, deviceId: String, auth: Auth, seriesId: String,
        ignoreCert: Boolean = false,
    ): List<EmbyItem> =
        ioSafe(TAG, emptyList()) {
            val url = "${apiBase(baseUrl, type)}/Shows/$seriesId/Seasons?UserId=${auth.userId}" +
                "&Fields=Overview,ProductionYear,RunTimeTicks,UserData,ChildCount"
            val text = getText(url, deviceId, auth.token, ignoreCert = ignoreCert) ?: return@ioSafe emptyList()
            jsonLenient.decodeFromString<EmbyItemsResponse>(text).Items
        }

    /** 某季的分集（按集号排）。 */
    suspend fun episodes(
        baseUrl: String, type: ServerType, deviceId: String, auth: Auth, seriesId: String, seasonId: String? = null,
        ignoreCert: Boolean = false,
    ): List<EmbyItem> = ioSafe(TAG, emptyList()) {
        val builder = "${apiBase(baseUrl, type)}/Shows/$seriesId/Episodes".toHttpUrlOrNull()?.newBuilder()
            ?: return@ioSafe emptyList()
        builder.addQueryParameter("UserId", auth.userId)
            .addQueryParameter("Fields", "Overview,ProductionYear,RunTimeTicks,UserData")
            .addQueryParameter("SortBy", "IndexNumber")
        if (!seasonId.isNullOrBlank()) builder.addQueryParameter("SeasonId", seasonId)
        val text = getText(builder.build().toString(), deviceId, auth.token, ignoreCert = ignoreCert)
            ?: return@ioSafe emptyList()
        jsonLenient.decodeFromString<EmbyItemsResponse>(text).Items
    }

    /**
     * 单条详情（带章节）。
     *
     * 列表接口的 Fields 不带 Chapters（200 条全带章节会把列表回包撑大），
     * 播放前单独取一次详情拿章节；失败返回 null，调用方按无章节照常播。
     */
    suspend fun item(
        baseUrl: String, type: ServerType, deviceId: String, auth: Auth, itemId: String,
        ignoreCert: Boolean = false,
    ): EmbyItem? = ioSafe(TAG, null) {
        val url = "${apiBase(baseUrl, type)}/Users/${auth.userId}/Items/$itemId".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("Fields", "Chapters,Overview,ProductionYear,RunTimeTicks,UserData")
            ?.build() ?: return@ioSafe null
        val text = getText(url.toString(), deviceId, auth.token, ignoreCert = ignoreCert) ?: return@ioSafe null
        jsonLenient.decodeFromString<EmbyItem>(text)
    }
}
