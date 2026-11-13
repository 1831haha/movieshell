package com.movieshell.data.source

import com.movieshell.core.log.LogBus
import com.movieshell.core.net.HttpClient
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
    private const val CLIENT_VERSION = "1.2.1"

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
        // v1.1.31：没写协议头的裸地址（如 192.168.1.10:8096）默认补 http://，
        // 否则 OkHttp 建请求直接炸，登录页只剩一句"登录失败"
        if (!u.contains("://")) u = "http://$u"
        if (u.endsWith("/emby", ignoreCase = true)) u = u.dropLast(5).removeSuffix("/")
        return u
    }

    /** 接口根：Emby 在 /emby 之下，Jellyfin 在 / 之下。 */
    fun apiBase(baseUrl: String, type: ServerType): String =
        normalizeBase(baseUrl) + if (type == ServerType.EMBY) "/emby" else ""

    /** Emby/Jellyfin 用 100 纳秒 tick 计时长：1ms = 10_000 tick。 */
    fun ticksToMs(ticks: Long): Long = (ticks / 10_000L).coerceAtLeast(0L)

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

    private fun client(): OkHttpClient = HttpClient.defaultClient()

    private suspend fun getText(url: String, deviceId: String, token: String?): String? {
        coroutineContext.ensureActive()
        val req = Request.Builder().url(url)
            .header("X-Emby-Authorization", authHeader(deviceId, token))
            .apply { if (token != null) header("X-Emby-Token", token) }
            .build()
        client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                LogBus.w(TAG, "GET ${resp.code} $url")
                return null
            }
            return resp.body?.string()
        }
    }

    private suspend fun postJson(url: String, deviceId: String, body: String, token: String? = null): String? {
        coroutineContext.ensureActive()
        val req = Request.Builder().url(url)
            .header("X-Emby-Authorization", authHeader(deviceId, token))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                LogBus.w(TAG, "POST ${resp.code} $url")
                return null
            }
            return resp.body?.string()
        }
    }

    /** 用户名密码登录 → token + userId。失败返回 null（密码错/地址错都一样，调用方只报“登录失败”）。 */
    suspend fun loginByPassword(
        baseUrl: String, type: ServerType, deviceId: String, username: String, password: String,
    ): Auth? = ioSafe(TAG, null) {
        val url = "${apiBase(baseUrl, type)}/Users/AuthenticateByName"
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString()
        val text = postJson(url, deviceId, body) ?: return@ioSafe null
        val result = jsonLenient.decodeFromString<EmbyAuthResult>(text)
        val token = result.AccessToken
        val userId = result.User?.Id.orEmpty()
        if (token.isBlank() || userId.isBlank()) null else Auth(token, userId)
    }

    /** API Key 登录：Key 本身当 token，再用 /Users/Me 换 userId。 */
    suspend fun loginByApiKey(baseUrl: String, type: ServerType, deviceId: String, apiKey: String): Auth? =
        ioSafe(TAG, null) {
            val url = "${apiBase(baseUrl, type)}/Users/Me"
            val text = getText(url, deviceId, apiKey) ?: return@ioSafe null
            val user = jsonLenient.decodeFromString<EmbyUser>(text)
            if (user.Id.isBlank()) null else Auth(apiKey, user.Id)
        }

    /** token 是否还有效（GET /Users/{id} 200 即有效）。 */
    suspend fun testAuth(baseUrl: String, type: ServerType, deviceId: String, auth: Auth): Boolean =
        ioSafe(TAG, false) {
            getText("${apiBase(baseUrl, type)}/Users/${auth.userId}", deviceId, auth.token) != null
        }

    /** 媒体库（电影库/剧集库等顶层视图）。 */
    suspend fun views(baseUrl: String, type: ServerType, deviceId: String, auth: Auth): List<EmbyItem> =
        ioSafe(TAG, emptyList()) {
            val url = "${apiBase(baseUrl, type)}/Users/${auth.userId}/Views"
            val text = getText(url, deviceId, auth.token) ?: return@ioSafe emptyList()
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
        val text = getText(builder.build().toString(), deviceId, auth.token) ?: return@ioSafe emptyList()
        jsonLenient.decodeFromString<EmbyItemsResponse>(text).Items
    }

    /** 某剧的季列表。 */
    suspend fun seasons(baseUrl: String, type: ServerType, deviceId: String, auth: Auth, seriesId: String): List<EmbyItem> =
        ioSafe(TAG, emptyList()) {
            val url = "${apiBase(baseUrl, type)}/Shows/$seriesId/Seasons?UserId=${auth.userId}" +
                "&Fields=Overview,ProductionYear,RunTimeTicks,UserData,ChildCount"
            val text = getText(url, deviceId, auth.token) ?: return@ioSafe emptyList()
            jsonLenient.decodeFromString<EmbyItemsResponse>(text).Items
        }

    /** 某季的分集（按集号排）。 */
    suspend fun episodes(
        baseUrl: String, type: ServerType, deviceId: String, auth: Auth, seriesId: String, seasonId: String? = null,
    ): List<EmbyItem> = ioSafe(TAG, emptyList()) {
        val builder = "${apiBase(baseUrl, type)}/Shows/$seriesId/Episodes".toHttpUrlOrNull()?.newBuilder()
            ?: return@ioSafe emptyList()
        builder.addQueryParameter("UserId", auth.userId)
            .addQueryParameter("Fields", "Overview,ProductionYear,RunTimeTicks,UserData")
            .addQueryParameter("SortBy", "IndexNumber")
        if (!seasonId.isNullOrBlank()) builder.addQueryParameter("SeasonId", seasonId)
        val text = getText(builder.build().toString(), deviceId, auth.token) ?: return@ioSafe emptyList()
        jsonLenient.decodeFromString<EmbyItemsResponse>(text).Items
    }
}
