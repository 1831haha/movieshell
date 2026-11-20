package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * v1.3.5：迅雷云盘分享解析（Kotlin 直写，逻辑照搬旧 xunlei.py）。
 *
 * 链路（游客可用，不登录）：
 * 1. 先拿验证码 token：POST https://xluser-ssl.xunlei.com/v1/shield/captcha/init，
 *    body 含 client_id、action "get:/drive/v1/share"、device_id、
 *    meta{package_name "pan.xunlei.com", client_version "1.92.89",
 *    captcha_sign, timestamp, user_id "0"}；
 *    captcha_sign 是本地 MD5 链式签名（纯本地算，不依赖登录）；
 * 2. 列目录：根目录 GET https://api-pan.xunlei.com/drive/v1/share，
 *    参数 share_id、pass_code、pass_code_token、limit=100、space 为空、
 *    thumbnail_size=SIZE_MEDIUM、scene=NORMAL、page_token；
 *    子目录改调 /drive/v1/share/detail，加 parent_id；
 *    pass_code_token 从首次响应里取，分页靠 next_page_token。
 *    请求头必须带 X-Captcha-Token、X-Device-Id（本地随机 32 位 hex）、
 *    X-Client-Id: Xqp0kJBXWhwaTpB6、X-Client-Version: 1.92.89、
 *    Referer: https://pan.xunlei.com/；
 * 3. 取直链：GET /drive/v1/share/file_info，
 *    参数 share_id、file_id、pass_code、pass_code_token、folder_id、
 *    space 为空、scene=NORMAL，
 *    返回 web_content_link、download_url、play_url，视频优先取 play_url。
 *
 * 待真机用真实分享链接验证（未验证，不可当已验证）：
 * - captcha_sign 的盐值与链式拼接顺序：旧报告只写明是本地 MD5 链式签名，
 *   未给出盐值与拼接顺序，本实现按 client_id、device_id、时间戳、
 *   action、版本号的链式 MD5 实现，失败时服务端会报 captcha_invalid；
 * - 游客拿到的 play_url 是否真不限速、能直接播；
 * - 直链时效：短时效、用时现取，本实现不缓存直链。
 * 无游客直链的文件须登录后转存，本版不做转存分支，如实报“需登录后转存”。
 */
object XunleiApi {

    private const val TAG = "ShareXunlei"
    private const val API = "https://api-pan.xunlei.com"
    private const val CAPTCHA_API = "https://xluser-ssl.xunlei.com"
    private const val CLIENT_ID = "Xqp0kJBXWhwaTpB6"
    private const val CLIENT_VERSION = "1.92.89"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val REFERER = "https://pan.xunlei.com/"

    private fun md5Hex(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 本地 MD5 链式签名（旧报告未给盐值与拼接顺序，本实现按常规链式拼，待真链验证）。 */
    fun captchaSign(deviceId: String, timestamp: String): String {
        val first = md5Hex("$CLIENT_ID$deviceId$timestamp")
        return md5Hex(first + "get:/drive/v1/share" + CLIENT_VERSION)
    }

    fun randomDeviceId(): String {
        val hex = "0123456789abcdef"
        val random = java.util.Random()
        return (1..32).map { hex[random.nextInt(hex.length)] }.joinToString("")
    }

    /** 拿验证码 token；失败返回空串。 */
    fun fetchCaptchaToken(
        client: OkHttpClient,
        deviceId: String,
    ): String {
        val timestamp = System.currentTimeMillis().toString()
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("action", "get:/drive/v1/share")
            .put("device_id", deviceId)
            .put(
                "meta", JSONObject()
                    .put("package_name", "pan.xunlei.com")
                    .put("client_version", CLIENT_VERSION)
                    .put("captcha_sign", captchaSign(deviceId, timestamp))
                    .put("timestamp", timestamp)
                    .put("user_id", "0"),
            )
        val resp = ShareHttp.postJson(client, "$CAPTCHA_API/v1/shield/captcha/init", "", UA, REFERER, body)
        val json = resp.json ?: return ""
        val data = json.optJSONObject("data") ?: json
        return data.optString("captcha_token")
            .ifBlank { json.optString("captcha_token") }
            .ifBlank { data.optString("token") }
    }

    private fun shareHeaders(deviceId: String, captchaToken: String): Map<String, String> = mapOf(
        "X-Captcha-Token" to captchaToken,
        "X-Device-Id" to deviceId,
        "X-Client-Id" to CLIENT_ID,
        "X-Client-Version" to CLIENT_VERSION,
    )

    /** 一页目录：返回（文件列表，下一页 token，新 pass_code_token）。 */
    fun listPage(
        client: OkHttpClient,
        deviceId: String,
        captchaToken: String,
        shareId: String,
        code: String,
        passCodeToken: String,
        parentId: String,
        pageToken: String,
    ): Triple<List<JSONObject>, String, String> {
        val path = if (parentId.isBlank()) "/drive/v1/share" else "/drive/v1/share/detail"
        val query = StringBuilder(path)
            .append("?share_id=").append(ShareHttp.enc(shareId))
            .append("&pass_code=").append(ShareHttp.enc(code))
            .append("&pass_code_token=").append(ShareHttp.enc(passCodeToken))
            .append("&limit=100&space=&thumbnail_size=SIZE_MEDIUM&scene=NORMAL")
            .append("&page_token=").append(ShareHttp.enc(pageToken))
        if (parentId.isNotBlank()) query.append("&parent_id=").append(ShareHttp.enc(parentId))
        val resp = ShareHttp.getJson(
            client, API + query.toString(), "", UA, REFERER,
            extraHeaders = shareHeaders(deviceId, captchaToken),
        )
        val json = resp.json ?: return Triple(emptyList(), "", passCodeToken)
        val arr = json.optJSONArray("files") ?: JSONArray()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        return Triple(
            out,
            json.optString("next_page_token").orEmpty(),
            json.optString("pass_code_token").ifBlank { passCodeToken },
        )
    }

    private fun isDir(f: JSONObject): Boolean {
        val kind = f.optString("kind").orEmpty()
        if (kind.isNotBlank()) return "folder" in kind
        if (f.has("is_dir")) return f.optBoolean("is_dir", false)
        return f.optString("type") == "folder"
    }

    /** 列一层目录（根目录 parentId=""；子目录传父目录 id）。 */
    fun listDir(
        shareUrl: String,
        parentId: String,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<ShareListing> {
        val (shareId, code) = parseShare(shareUrl)
        if (shareId.isBlank()) {
            return ShareResult.Err("这个链接认不出来，换个分享链接再试")
        }
        val deviceId = randomDeviceId()
        val captchaToken = fetchCaptchaToken(client, deviceId)
        if (captchaToken.isBlank()) {
            return ShareResult.Err("迅雷分享打不开：验证码没过，换个链接再试")
        }
        val files = ArrayList<ShareFile>()
        var pageToken = ""
        var passCodeToken = ""
        while (true) {
            val (items, next, token) = listPage(
                client, deviceId, captchaToken, shareId, code, passCodeToken, parentId, pageToken,
            )
            passCodeToken = token
            for (f in items) {
                val name = f.optString("name").orEmpty()
                files.add(
                    ShareFile(
                        fid = f.optString("id").orEmpty(),
                        name = name,
                        size = f.optLong("size", 0L),
                        isDir = isDir(f),
                        dirKey = f.optString("id").orEmpty(),
                        folderId = parentId,
                    ),
                )
            }
            pageToken = next
            if (pageToken.isBlank() || files.size >= 200) break
        }
        if (files.isEmpty()) {
            return ShareResult.Err("这个分享里是空的，或文件夹里没有文件")
        }
        return ShareResult.Ok(
            ShareListing(
                platform = SharePlatform.XUNLEI,
                shareUrl = shareUrl,
                title = files.firstOrNull { !it.isDir }?.name.orEmpty(),
                files = files,
                session = ShareSession.Xunlei(shareId, code, passCodeToken),
            ),
        )
    }

    /** 解析迅雷分享链接：返回（share_id，提取码）。 */
    fun parseShare(url: String): Pair<String, String> {
        val m = Regex("""pan\.xunlei\.com/s/([A-Za-z0-9_-]+)""").find(url) ?: return "" to ""
        val shareId = m.groupValues[1]
        val code = Regex("""[?&]pwd=([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1).orEmpty()
            .ifEmpty { Regex("""密码[:：]?\s*([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1).orEmpty() }
        return shareId to code
    }

    /** 取直链：返回（play_url，download_url，web_content_link），全空表示无直链。 */
    private fun fileInfo(
        client: OkHttpClient,
        deviceId: String,
        captchaToken: String,
        session: ShareSession.Xunlei,
        file: ShareFile,
    ): Triple<String, String, String> {
        // v1.3.6：查询串里的值一律编码——pass_code_token 常含 +/=，不编码会被截断。
        val query = "/drive/v1/share/file_info?share_id=${ShareHttp.enc(session.shareId)}" +
            "&file_id=${ShareHttp.enc(file.fid)}&pass_code=${ShareHttp.enc(session.code)}" +
            "&pass_code_token=${ShareHttp.enc(session.passCodeToken)}" +
            "&folder_id=${ShareHttp.enc(file.folderId)}&space=&scene=NORMAL"
        val resp = ShareHttp.getJson(
            client, API + query, "", UA, REFERER,
            extraHeaders = shareHeaders(deviceId, captchaToken),
        )
        val json = resp.json ?: return Triple("", "", "")
        return Triple(
            json.optString("play_url").orEmpty(),
            json.optString("download_url").orEmpty(),
            json.optString("web_content_link").orEmpty(),
        )
    }

    /** 取一集的播放地址：视频优先取 play_url。无游客直链时如实报须在迅雷 App 内转存（本版不做转存分支）。 */
    fun resolve(
        listing: ShareListing,
        file: ShareFile,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<PlayLink> {
        val session = listing.session as? ShareSession.Xunlei
            ?: return ShareResult.Err("会话丢了，重新打开这个分享再试")
        val deviceId = randomDeviceId()
        val captchaToken = fetchCaptchaToken(client, deviceId)
        if (captchaToken.isBlank()) {
            return ShareResult.Err("迅雷解析失败：验证码没过，换个链接再试")
        }
        val (playUrl, downloadUrl, webLink) = fileInfo(client, deviceId, captchaToken, session, file)
        val url = playUrl.ifBlank { downloadUrl }.ifBlank { webLink }
        if (url.isBlank()) {
            LogBus.w(TAG, "迅雷无游客直链：须登录后转存，本版不做转存分支")
            return ShareResult.Err("这个迅雷文件没有游客直链，须在迅雷 App 内转存后才能播（本版暂不支持登录后转存）")
        }
        return ShareResult.Ok(
            PlayLink(url, mapOf("Referer" to REFERER, "User-Agent" to UA)),
        )
    }
}
