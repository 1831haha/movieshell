package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.3.5：阿里云盘分享解析（Kotlin 直写，逻辑照搬旧 ali.py）。
 *
 * 链路（游客可用，不登录）：
 * 1. 取分享 token：POST https://api.alipan.com/v2/share_link/get_share_token，
 *    body {"share_id", "share_pwd"}，官方文档明写无需鉴权；
 * 2. 列文件：POST https://api.aliyundrive.com/adrive/v3/file/list，
 *    body {"share_id", "parent_file_id", "limit": 200}，头带 x-share-token；
 * 3. 播视频先走转码：POST /v2/file/get_share_link_video_preview_play_info，
 *    body {"share_id", "file_id", "category": "live_transcoding"}，
 *    取 live_transcoding_task_list 里的地址，优先 FHD 再 HD 再 SD；
 * 4. 原画直链作备选：POST /v2/file/get_share_link_download_url，
 *    body {"share_id", "file_id", "expire_sec": 600}。
 * share_token 剩余有效期不足 600 秒时刷新。
 *
 * 待真机用真实分享链接验证（未验证，不可当已验证）：
 * - 匿名原画直链是否真能出（旧规则只作备选，转码路线相对稳）；
 * - require_login 的分享（分享者设成须登录查看）游客打不开，属预期失败；
 * - 转码清晰度与速度上限、直链实际有效期，待真链验证。
 * 登录 token（手动填的）若已存，list/play 请求顺带 Authorization
 * 头，require_login 的分享或许能开——同样未验证，只当尽力一试。
 */
object AliyunApi {

    private const val TAG = "ShareAliyun"
    private const val TOKEN_API = "https://api.alipan.com"
    private const val API = "https://api.aliyundrive.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.alipan.com/"

    /** 分享 token：返回（token，过期毫秒时间戳）。 */
    fun fetchToken(
        client: OkHttpClient,
        shareId: String,
        pwd: String,
    ): Pair<String, Long> {
        val body = JSONObject().put("share_id", shareId).put("share_pwd", pwd)
        val resp = ShareHttp.postJson(client, "$TOKEN_API/v2/share_link/get_share_token", "", UA, REFERER, body)
        val json = resp.json ?: return "" to 0L
        val token = json.optString("share_token").orEmpty()
        if (token.isBlank()) return "" to 0L
        val expiresIn = json.optLong("expires_in", 3600L)
        return token to (System.currentTimeMillis() + expiresIn * 1000)
    }

    private fun authHeaders(token: String): Map<String, String> {
        val headers = mutableMapOf("x-share-token" to token)
        val saved = ShareAuth.getTokenJson(SharePlatform.ALIYUN)
        if (saved.isNotBlank()) {
            val access = runCatching { JSONObject(saved).optString("access_token") }.getOrDefault("")
            if (access.isNotBlank()) headers["Authorization"] = "Bearer $access"
        }
        return headers
    }

    private fun listFiles(
        client: OkHttpClient,
        token: String,
        shareId: String,
        parentFileId: String,
    ): List<JSONObject> {
        val body = JSONObject()
            .put("share_id", shareId)
            .put("parent_file_id", parentFileId.ifBlank { "root" })
            .put("limit", 200)
        val resp = ShareHttp.postJson(
            client, "$API/adrive/v3/file/list", "", UA, REFERER, body,
            extraHeaders = authHeaders(token),
        )
        val json = resp.json ?: return emptyList()
        val items = json.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<JSONObject>(items.length())
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    /** 列一层目录（根目录 parentFileId="root"；folder 链接进来的从 folder_id 起）。 */
    fun listDir(
        shareUrl: String,
        parentFileId: String,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<ShareListing> {
        val (shareId, pwd, folderId) = parseShare(shareUrl)
        if (shareId.isBlank()) {
            return ShareResult.Err("这个链接认不出来，换个分享链接再试")
        }
        val (token, expiresAt) = fetchToken(client, shareId, pwd)
        if (token.isBlank()) {
            return ShareResult.Err("阿里分享打不开：可能已失效、要提取码，或接口变了")
        }
        val root = parentFileId.ifBlank { folderId.ifBlank { "root" } }
        val items = listFiles(client, token, shareId, root)
        val files = items.map { f ->
            ShareFile(
                fid = f.optString("file_id").orEmpty(),
                name = f.optString("name").orEmpty(),
                size = f.optLong("size", 0L),
                isDir = f.optString("type") == "folder",
                dirKey = f.optString("file_id").orEmpty(),
            )
        }
        return ShareResult.Ok(
            ShareListing(
                platform = SharePlatform.ALIYUN,
                shareUrl = shareUrl,
                title = files.firstOrNull { !it.isDir }?.name.orEmpty(),
                files = files,
                session = ShareSession.Aliyun(shareId, pwd, token, expiresAt),
            ),
        )
    }

    /** 解析 alipan/aliyundrive 分享链接：返回（share_id，提取码，folder_id）。 */
    fun parseShare(url: String): Triple<String, String, String> {
        val m = Regex("""alipan\.com/s/([A-Za-z0-9_-]+)""").find(url)
            ?: Regex("""aliyundrive\.com/s/([A-Za-z0-9_-]+)""").find(url)
            ?: return Triple("", "", "")
        val shareId = m.groupValues[1]
        val pwd = Regex("""[?&]pwd=([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1).orEmpty()
            .ifEmpty { Regex("""密码[:：]?\s*([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1).orEmpty() }
        val folderId = Regex("""/folder/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1).orEmpty()
        return Triple(shareId, pwd, folderId)
    }

    private fun transcodeUrl(
        client: OkHttpClient,
        session: ShareSession.Aliyun,
        fileId: String,
    ): String {
        val body = JSONObject()
            .put("share_id", session.shareId)
            .put("file_id", fileId)
            .put("category", "live_transcoding")
        val resp = ShareHttp.postJson(
            client, "$API/v2/file/get_share_link_video_preview_play_info", "", UA, REFERER, body,
            extraHeaders = authHeaders(session.token),
        )
        val json = resp.json ?: return ""
        val tasks = json.optJSONObject("video_preview_play_info")
            ?.optJSONArray("live_transcoding_task_list") ?: JSONArray()
        var best = ""
        for (want in listOf("FHD", "HD", "SD", "")) {
            for (i in 0 until tasks.length()) {
                val t = tasks.optJSONObject(i) ?: continue
                val tid = t.optString("template_id").orEmpty()
                val url = t.optString("url").ifBlank { t.optString("preview_url") }
                if (url.isNotBlank() && (want.isBlank() || want in tid)) return url
                if (url.isNotBlank() && best.isBlank()) best = url
            }
        }
        return best
    }

    private fun downloadUrl(
        client: OkHttpClient,
        session: ShareSession.Aliyun,
        fileId: String,
    ): String {
        val body = JSONObject()
            .put("share_id", session.shareId)
            .put("file_id", fileId)
            .put("expire_sec", 600)
        val resp = ShareHttp.postJson(
            client, "$API/v2/file/get_share_link_download_url", "", UA, REFERER, body,
            extraHeaders = authHeaders(session.token),
        )
        return resp.json?.optString("url").orEmpty()
    }

    /** 取一集的播放地址：先转码（FHD>HD>SD），不成再试原画直链。 */
    fun resolve(
        listing: ShareListing,
        file: ShareFile,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<PlayLink> {
        val session = listing.session as? ShareSession.Aliyun
            ?: return ShareResult.Err("会话丢了，重新打开这个分享再试")
        val liveSession = if (session.tokenExpiresAtMs - System.currentTimeMillis() < 600_000) {
            val (token, expiresAt) = fetchToken(client, session.shareId, session.pwd)
            if (token.isBlank()) {
                return ShareResult.Err("阿里分享打不开：分享 token 拿不到了，换个链接再试")
            }
            session.copy(token = token, tokenExpiresAtMs = expiresAt)
        } else {
            session
        }
        var url = transcodeUrl(client, liveSession, file.fid)
        if (url.isBlank()) url = downloadUrl(client, liveSession, file.fid)
        if (url.isBlank()) {
            LogBus.w(TAG, "阿里转码与直链都没拿到")
            return ShareResult.Err("阿里解析失败：转码和直链都没拿到，可能是分享须登录或接口变了")
        }
        return ShareResult.Ok(
            PlayLink(url, mapOf("Referer" to REFERER, "User-Agent" to UA)),
        )
    }
}
