package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
import okhttp3.FormBody
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.3.5：百度网盘分享解析（Kotlin 直写，逻辑照搬旧 baidu.py /
 * PanShareCheck.checkBaidu）。
 *
 * 链路：wxlist 列文件 → tplconfig 取 sign → sharedownload 取 dlink。
 * 取 dlink 分两档，按顺序走，拿到就停：
 * 1. nolimit（登录态，clienttype=12 + type=nolimit）——SVIP 直链；
 * 2. 普通（clienttype=0）——游客/普通登录大概率只给一段加密串，播不了。
 *
 * 已实测（2026-11-10 沙箱出口直连 pan.baidu.com）：
 * - wxlist 通：POST /share/wxlist，shorturl 必须带 1 前缀全码
 *   （用不带 1 的码报 errno:2）；返回 uk/shareid/seckey/list；
 * - tplconfig 通：sign 40 位 hex、timestamp 5 分钟有效；
 * - sharedownload 游客卡住：clienttype=0 只给 3992 字符 base64 密文，
 *   不是直链；clienttype=12 + type=nolimit 报 errno 9019 need verify。
 *   公开实现证实 nolimit 路线要带已登录 SVIP 的 BDUSS 才能出明文 dlink。
 * - verify 接口不可靠（同一链接两次结果不同），本实现不用它做检测。
 * 回源头：大于约 20MB 的文件回源须带 User-Agent: pan.baidu.com。
 * dlink 短时效（sign 5 分钟），现取现用，绝不缓存。
 *
 * 注意：百度登录后全程沙箱验不了（无百度 SVIP 账号），登录态下 dlink
 * 能不能出、非 SVIP 能不能出，真机待验。
 */
object BaiduApi {

    private const val TAG = "ShareBaidu"
    private const val BASE = "https://pan.baidu.com"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Redmi K80) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
    private const val REFERER = "https://pan.baidu.com/"

    enum class Check { OK, DEAD, UNKNOWN }

    /** 播前检测：wxlist 一步定生死（照搬 PanShareCheck.checkBaidu）。 */
    fun check(shareUrl: String, client: OkHttpClient = ShareHttp.defaultClient()): Check {
        val (full, pwd) = ShareLink.parse(shareUrl) ?: return Check.UNKNOWN
        val (items, _, _, _, _, errno) = wxlist(client, full, pwd)
        return when {
            items.isNotEmpty() -> Check.OK
            errno == 2 || errno == -12 -> Check.DEAD
            else -> Check.UNKNOWN
        }
    }

    /** wxlist 一次：返回（文件列表, seckey, uk, shareid, 标题）。 */
    fun wxlist(
        client: OkHttpClient,
        full: String,
        pwd: String,
        dirPath: String = "",
        root: String = "1",
    ): Quintet {
        val form = FormBody.Builder()
            .add("shorturl", full).add("dir", dirPath).add("root", root).add("pwd", pwd)
            .add("page", "1").add("num", "1000").add("order", "time").build()
        val resp = ShareHttp.postForm(
            client,
            "$BASE/share/wxlist?channel=weixin&version=2.2.2&clienttype=25&web=1",
            UA, REFERER, form,
        )
        val json = resp.json ?: return Quintet(emptyList(), "", "", "", "", -1)
        val errno = json.optInt("errno", -1)
        if (errno != 0) return Quintet(emptyList(), "", "", "", "", errno)
        val data = json.optJSONObject("data") ?: return Quintet(emptyList(), "", "", "", "", errno)
        val list = data.optJSONArray("list") ?: JSONArray()
        val files = ArrayList<JSONObject>(list.length())
        for (i in 0 until list.length()) {
            list.optJSONObject(i)?.let { files.add(it) }
        }
        return Quintet(
            files,
            data.optString("seckey").orEmpty(),
            data.optString("uk").orEmpty(),
            data.optString("shareid").orEmpty(),
            data.optString("title").orEmpty(),
            errno,
        )
    }

    data class Quintet(
        val files: List<JSONObject>,
        val seckey: String,
        val uk: String,
        val shareId: String,
        val title: String,
        val errno: Int,
    )

    /** wxlist 的 isdir 是字符串 "0"/"1"，直接当布尔值会把 "0" 判成真。 */
    fun isDir(f: JSONObject): Boolean = f.optString("isdir", "0") == "1"

    fun toShareFile(f: JSONObject, prefix: String): ShareFile {
        val name = prefix + f.optString("server_filename").orEmpty()
        return ShareFile(
            fid = f.optString("fs_id").orEmpty(),
            name = name,
            size = f.optLong("size", 0L),
            isDir = isDir(f),
            dirKey = f.optString("path").orEmpty(),
        )
    }

    /** 列一层目录（根目录 dirPath=""）。 */
    fun listDir(
        shareUrl: String,
        dirPath: String,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<ShareListing> {
        val (full, pwd) = ShareLink.parse(shareUrl)
            ?: return ShareResult.Err("这个链接认不出来，换个分享链接再试")
        val (items, seckey, uk, shareId, title, _) = wxlist(client, full, pwd, dirPath, if (dirPath.isBlank()) "1" else "0")
        if (items.isEmpty() && seckey.isBlank()) {
            return ShareResult.Err("百度分享打不开：可能已失效或提取码不对")
        }
        val files = items.map { toShareFile(it, "") }
        return ShareResult.Ok(
            ShareListing(
                platform = SharePlatform.BAIDU,
                shareUrl = shareUrl,
                title = title.ifBlank { files.firstOrNull()?.name.orEmpty() },
                files = files,
                session = ShareSession.Baidu(uk, shareId, seckey, full, pwd),
            ),
        )
    }

    private fun tplconfig(client: OkHttpClient, full: String): Pair<String, String> {
        val url = "$BASE/share/tplconfig?fields=sign,timestamp&channel=chunlei&web=1" +
            "&app_id=250528&clienttype=0&surl=$full"
        val resp = ShareHttp.getJson(client, url, "", UA, REFERER)
        val json = resp.json ?: return "" to ""
        if (json.optInt("errno", -1) != 0) return "" to ""
        val data = json.optJSONObject("data") ?: return "" to ""
        return data.optString("sign").orEmpty() to data.optString("timestamp").orEmpty()
    }

    /** 取明文 dlink；拿到加密串或失败返回空串。 */
    private fun sharedownload(
        client: OkHttpClient,
        session: ShareSession.Baidu,
        sign: String,
        timestamp: String,
        fsId: String,
        clienttype: Int,
        nolimit: Boolean,
        cookie: String,
    ): String {
        val params = "channel=chunlei&clienttype=$clienttype&app_id=250528" +
            "&sign=$sign&timestamp=$timestamp&web=1" + if (nolimit) "&type=nolimit" else ""
        val form = FormBody.Builder()
            .add("encrypt", "0").add("product", "share")
            .add("primaryid", session.shareId).add("shareid", session.shareId)
            .add("uk", session.uk).add("fid_list", "[$fsId]")
            .add("extra", JSONObject().put("sekey", session.seckey).toString())
            .build()
        val resp = ShareHttp.postForm(client, "$BASE/api/sharedownload?$params", UA, REFERER, form, cookie)
        val json = resp.json ?: return ""
        if (json.optInt("errno", -1) != 0) return ""
        val list = json.optJSONObject("data")?.opt("list") ?: return ""
        if (list is JSONArray && list.length() > 0) {
            return list.optJSONObject(0)?.optString("dlink").orEmpty()
        }
        return ""
    }

    /** 取一集的播放地址。游客/非 SVIP 拿不到明文直链，如实失败。 */
    fun resolve(
        listing: ShareListing,
        file: ShareFile,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<PlayLink> {
        val session = listing.session as? ShareSession.Baidu
            ?: return ShareResult.Err("会话丢了，重新打开这个分享再试")
        val cookie = ShareAuth.getCookie(SharePlatform.BAIDU)
        val (sign, timestamp) = tplconfig(client, session.full)
        if (sign.isBlank()) {
            return ShareResult.Err("百度解析失败：没拿到签名，换个链接再试")
        }
        var url = ""
        if (cookie.isNotBlank()) {
            url = sharedownload(client, session, sign, timestamp, file.fid, 12, true, cookie)
        }
        if (url.isBlank()) {
            url = sharedownload(client, session, sign, timestamp, file.fid, 0, false, cookie)
        }
        if (url.isBlank()) {
            LogBus.w(TAG, "百度 dlink 为空：游客只给加密串，或登录不是 SVIP")
            return if (cookie.isBlank()) {
                ShareResult.Err("百度分享要登录才能播：取直链需 SVIP，先去登录页登录", needLogin = true)
            } else {
                ShareResult.Err("百度解析失败：分享可能失效、登录已过期，或账号不是 SVIP", needLogin = true)
            }
        }
        return ShareResult.Ok(
            PlayLink(
                url = url,
                headers = mapOf("User-Agent" to "pan.baidu.com", "Referer" to REFERER),
            ),
        )
    }
}
