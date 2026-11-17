package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.3.5：光鸭网盘分享解析（Kotlin 直写，逻辑照搬旧 guangya.py）。
 *
 * 链路（游客可用，不登录）：
 * 1. 取分享 token：POST https://api.guangyapan.com/userres/v1/get_share_access_token，
 *    body {"shareId", "code"}，返回 data.accessToken（分享级 token，无需登录；
 *    返回码 209 表示待验证）；
 * 2. 列文件：POST /userres/v1/get_share_page_files_list，
 *    body {"accessToken", "folderPath", "orderBy", "sortType"}；
 * 3. 取直链：POST /userres/v1/get_share_download_url，
 *    body {"fileId", "accessToken", "orderId"}，返回 signedURL，现取现用。
 *
 * 直链带 need_pay 或 pay_reason_code 时如实失败，不假装成功。
 * orderBy 与 sortType 的取值、orderId 的含义，旧报告未给出具体值，
 * 本实现按常规值发送，须拿有效分享验证后修正。
 *
 * 待真机用真实分享链接验证（未验证，不可当已验证）：
 * - 有效分享端到端 1 到 3 步全程（旧报告只实测到过期分享的 201 与错参的 112）；
 * - 大文件是否触发付费分支（need_pay 的阈值未核实）；
 * - folderPath 根目录取值、子目录路径写法、文件列表字段名是否与本实现一致。
 */
object GuangyaApi {

    private const val TAG = "ShareGuangya"
    private const val API = "https://api.guangyapan.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.guangyapan.com/"

    /** 取分享级 accessToken；209 待验证或其它失败返回空串。 */
    fun fetchAccessToken(client: OkHttpClient, shareId: String, code: String): String {
        val body = JSONObject().put("shareId", shareId).put("code", code)
        val resp = ShareHttp.postJson(client, "$API/userres/v1/get_share_access_token", "", UA, REFERER, body)
        val json = resp.json ?: return ""
        if (json.optInt("code", -1) != 200) return ""
        return json.optJSONObject("data")?.optString("accessToken").orEmpty()
    }

    private fun listFiles(client: OkHttpClient, token: String, folderPath: String): List<JSONObject> {
        val body = JSONObject()
            .put("accessToken", token)
            .put("folderPath", folderPath)
            .put("orderBy", "name")
            .put("sortType", "asc")
        val resp = ShareHttp.postJson(client, "$API/userres/v1/get_share_page_files_list", "", UA, REFERER, body)
        val json = resp.json ?: return emptyList()
        if (json.optInt("code", -1) != 200) return emptyList()
        val data = json.opt("data") ?: return emptyList()
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("list") ?: data.optJSONArray("files")
                ?: data.optJSONArray("records") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    private fun isDir(f: JSONObject): Boolean {
        if (f.has("isDir")) return f.optBoolean("isDir", false)
        if (f.has("is_dir")) return f.optBoolean("is_dir", false)
        return f.optString("type") in listOf("folder", "dir")
    }

    private fun fileName(f: JSONObject): String =
        f.optString("fileName").ifBlank { f.optString("file_name") }.ifBlank { f.optString("name") }

    private fun fileId(f: JSONObject): String =
        f.optString("fileId").ifBlank { f.optString("file_id") }.ifBlank { f.optString("id") }

    /** 列一层目录（根目录 folderPath=""；子目录传父目录路径）。 */
    fun listDir(
        shareUrl: String,
        folderPath: String,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<ShareListing> {
        val (shareId, code) = parseShare(shareUrl)
        if (shareId.isBlank()) {
            return ShareResult.Err("这个链接认不出来，换个分享链接再试")
        }
        val token = fetchAccessToken(client, shareId, code)
        if (token.isBlank()) {
            return ShareResult.Err("光鸭分享打不开：可能已失效、要验证码，或接口变了")
        }
        val items = listFiles(client, token, folderPath)
        val files = items.map { f ->
            val name = fileName(f)
            ShareFile(
                fid = fileId(f),
                name = name,
                size = f.optLong("fileSize", f.optLong("size", 0L)),
                isDir = isDir(f),
                dirKey = if (folderPath.isBlank()) "/$name" else "${folderPath.trimEnd('/')}/$name",
            )
        }
        return ShareResult.Ok(
            ShareListing(
                platform = SharePlatform.GUANGYA,
                shareUrl = shareUrl,
                title = files.firstOrNull { !it.isDir }?.name.orEmpty(),
                files = files,
                session = ShareSession.Guangya(shareId, code, token),
            ),
        )
    }

    /** 解析光鸭分享链接：返回（share_id，提取码）。 */
    fun parseShare(url: String): Pair<String, String> {
        val m = Regex("""guangyapan\.com/s/([A-Za-z0-9_-]+)""").find(url) ?: return "" to ""
        val shareId = m.groupValues[1]
        val code = Regex("""[?&](?:pwd|code)=([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1).orEmpty()
            .ifEmpty { Regex("""密码[:：]?\s*([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1).orEmpty() }
        return shareId to code
    }

    /** 取直链：返回（直链，是否需付费，付费原因码）。 */
    private fun downloadUrl(
        client: OkHttpClient,
        token: String,
        fileId: String,
    ): Triple<String, Boolean, String> {
        val body = JSONObject()
            .put("fileId", fileId)
            .put("accessToken", token)
            .put("orderId", "")
        val resp = ShareHttp.postJson(client, "$API/userres/v1/get_share_download_url", "", UA, REFERER, body)
        val json = resp.json ?: return Triple("", false, "")
        val data = json.optJSONObject("data") ?: JSONObject()
        val needPay = data.optBoolean("need_pay", false)
        val reason = data.optString("pay_reason_code").orEmpty()
        if (needPay) return Triple("", true, reason)
        val url = data.optString("signedURL")
            .ifBlank { data.optString("signedUrl") }
            .ifBlank { data.optString("downloadUrl") }
        return Triple(url, false, reason)
    }

    /** 取一集的播放地址。需付费时如实失败，不返回假直链。 */
    fun resolve(
        listing: ShareListing,
        file: ShareFile,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<PlayLink> {
        val session = listing.session as? ShareSession.Guangya
            ?: return ShareResult.Err("会话丢了，重新打开这个分享再试")
        val (url, needPay, reason) = downloadUrl(client, session.accessToken, file.fid)
        if (needPay) {
            val msg = "光鸭分享下载需付费" + if (reason.isNotBlank()) "（$reason）" else ""
            LogBus.w(TAG, msg)
            return ShareResult.Err(msg)
        }
        if (url.isBlank()) {
            return ShareResult.Err("光鸭解析失败：可能是分享失效、付费分支或接口变了，换个链接再试")
        }
        return ShareResult.Ok(
            PlayLink(url, mapOf("Referer" to REFERER, "User-Agent" to UA)),
        )
    }
}
