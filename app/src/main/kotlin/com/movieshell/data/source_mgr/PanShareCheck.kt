package com.movieshell.data.source_mgr

import com.movieshell.core.log.LogBus
import com.movieshell.data.source.PanShareLinks
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.1.23 扩充：播放前有效性检测——点播前先轻量验一下分享还有没有效，
 * 失效/过期直接给人话提示，别等到播放器黑屏才知道。
 *
 * - 夸克/UC：调一次 sharepage/token（游客先试）：
 *   code 0 → 分享有效；41027/41011 → 分享已失效；
 *   31001 且无登录态 → 分享要先登录；31001 且有登录态 → 登录已过期。
 * - 百度：调一次 share/wxlist（带提取码）：
 *   errno 0 → 分享有效；errno 2/-12 → 分享已失效或提取码不对。
 *   注意百度 verify 接口时通时不通（同链接两次结果不同），不用它做检测。
 * - 阿里/光鸭/迅雷：暂不做播前检测，直接 UNKNOWN 交给规则正常跑。
 * 其他情况一律 UNKNOWN，交给规则正常跑，不因检测误拦。
 */
object PanShareCheck {

    private const val TAG = "PanCheck"
    private const val QUARK_API = "https://drive-pc.quark.cn/1/clouddrive/"
    private const val QUARK_PR = "pr=ucpro&fr=pc"
    private const val QUARK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val UC_API = "https://pc-api.uc.cn/1/clouddrive/"
    private const val UC_PR = "pr=UCBrowser&fr=pc"
    private const val UC_UA = "Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    private const val BAIDU_BASE = "https://pan.baidu.com"
    private const val BAIDU_UA = "Mozilla/5.0 (Linux; Android 14; Redmi K80) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    enum class Result { OK, DEAD, NEED_LOGIN, LOGIN_EXPIRED, UNKNOWN }

    /** 平台 + 分享链接 + 登录态 Cookie（无登录传空串）。base 覆盖仅供单测用。 */
    fun check(
        platform: PanShareLinks.Platform, shareUrl: String, cookie: String,
        quarkUcBase: String? = null, baiduBase: String? = null,
        client: OkHttpClient = defaultClient(),
    ): Result {
        val (id, code) = PanShareLinks.parse(shareUrl) ?: return Result.UNKNOWN
        return when (platform) {
            PanShareLinks.Platform.QUARK -> checkQuarkUc(
                quarkUcBase ?: QUARK_API, QUARK_PR, QUARK_UA, "https://pan.quark.cn/",
                id, code, cookie, client,
            )
            PanShareLinks.Platform.UC -> checkQuarkUc(
                quarkUcBase ?: UC_API, UC_PR, UC_UA, "https://drive.uc.cn/",
                id, code, cookie, client,
            )
            PanShareLinks.Platform.BAIDU -> checkBaidu(baiduBase ?: BAIDU_BASE, id, code, client)
            PanShareLinks.Platform.ALIYUN -> Result.UNKNOWN
            PanShareLinks.Platform.GUANGYA -> Result.UNKNOWN
            PanShareLinks.Platform.XUNLEI -> Result.UNKNOWN
        }
    }

    /** 夸克/UC 共用：token 一步定生死。 */
    fun checkQuarkUc(
        apiBase: String, pr: String, ua: String, referer: String,
        pwdId: String, passcode: String, cookie: String,
        client: OkHttpClient = defaultClient(),
    ): Result {
        val guest = postToken(client, apiBase, pr, ua, referer, pwdId, passcode, "")
        if (guest != null) {
            when (guest) {
                0 -> return Result.OK
                41027, 41011 -> return Result.DEAD
                31001 -> {
                    if (cookie.isBlank()) return Result.NEED_LOGIN
                    val authed = postToken(client, apiBase, pr, ua, referer, pwdId, passcode, cookie)
                    return when (authed) {
                        0 -> Result.OK
                        31001 -> Result.LOGIN_EXPIRED
                        41027, 41011 -> Result.DEAD
                        else -> Result.UNKNOWN
                    }
                }
            }
        }
        return Result.UNKNOWN
    }

    /** 百度：wxlist 一步定生死（verify 不可靠，不用）。 */
    fun checkBaidu(
        baiduBase: String, shortUrl: String, pwd: String,
        client: OkHttpClient = defaultClient(),
    ): Result {
        val form = FormBody.Builder()
            .add("shorturl", shortUrl)
            .add("dir", "")
            .add("root", "1")
            .add("pwd", pwd)
            .add("page", "1")
            .add("num", "100")
            .add("order", "time")
            .build()
        val req = Request.Builder()
            .url("$baiduBase/share/wxlist?channel=weixin&version=2.2.2&clienttype=25&web=1")
            .header("User-Agent", BAIDU_UA)
            .header("Referer", "https://pan.baidu.com/")
            .post(form)
            .build()
        val errno = runCatching {
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optInt("errno", -1)
            }
        }.onFailure { LogBus.w(TAG, "百度检测请求失败：${it.message}") }.getOrDefault(-1)
        return when (errno) {
            0 -> Result.OK
            2, -12 -> Result.DEAD
            else -> Result.UNKNOWN
        }
    }

    /** token 一步返回的 code；网络失败返回 null。 */
    private fun postToken(
        client: OkHttpClient, apiBase: String, pr: String, ua: String, referer: String,
        pwdId: String, passcode: String, cookie: String,
    ): Int? {
        val body = JSONObject().put("pwd_id", pwdId).put("passcode", passcode)
        val req = Request.Builder()
            .url("${apiBase}share/sharepage/token?$pr")
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Content-Type", "application/json")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .post(body.toString().toRequestBody(JSON))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optInt("code", -1)
            }
        }.onFailure { LogBus.w(TAG, "token 检测请求失败：${it.message}") }.getOrNull()
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
}
