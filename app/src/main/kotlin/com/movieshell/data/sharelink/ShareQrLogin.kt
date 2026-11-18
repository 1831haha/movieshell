package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * v1.3.5：分享链接播放的扫码登录（照搬 v1.1.31 砍盘前 PanQrLogin 的
 * 夸克/UC/百度三家；阿里扫码按 2026-11-11 用户决定不做，光鸭/迅雷
 * 取二维码端点从未钉死，两家只留手动填 token）。
 *
 * 链路证据（旧文件头注释）：
 * - 夸克/UC：官方 bundle 实证——GET uop getTokenForQrcodeLogin 取 token，
 *   二维码内容拼 su.quark.cn/4_eMHBJ（本地画），轮询
 *   getServiceTicketByQrcodeToken（2 秒一次，status 2000000 取
 *   service_ticket），doAuth 调 bizHost/account/info?st=tmpST 换会话，
 *   Set-Cookie 落 __puus 等。
 * - 百度：getqrcode 已实测通（返回 imgurl＋sign，图片直接用），轮询
 *   channel/unicast 按 BaiduPCS-Go 参数接。
 * - UC：与夸克同一套 uop CAS，client_id 换 381、扫码页换 broccoli
 *   域名；取 token 接口返回结构与夸克一致已实测，扫码页 URL 拼接是
 *   推断，真机扫码验证。
 *
 * 注意：夸克扫码 1.1.29/1.1.30 真机卡在换取一步（码扫上、票拿到、
 * 换取失败），1.1.30 已改成同 jar 换取 + lw=scan，但仍未真机验通；
 * 扫码失败时弹窗必须给手动输入 Cookie 的退路，不许只剩一条死路。
 * Cookie 值绝不写进日志。
 */
object ShareQrLogin {

    private const val TAG = "ShareQr"

    internal const val UOP_BASE = "https://uop.quark.cn"
    internal const val QUARK_BIZ = "https://pan.quark.cn"
    internal const val UC_BIZ = "https://drive.uc.cn"
    internal const val PASSPORT_BASE = "https://passport.baidu.com"

    internal const val QUARK_CLIENT_ID = "532"
    internal const val UC_CLIENT_ID = "381"
    internal const val UOP_API_V = "1.2"

    private fun uopTokenUrl(uopBase: String, clientId: String): String =
        "$uopBase/cas/ajax/getTokenForQrcodeLogin?client_id=$clientId&v=$UOP_API_V&request_id=${UUID.randomUUID()}"

    private fun uopPollUrl(uopBase: String, clientId: String, token: String): String =
        "$uopBase/cas/ajax/getServiceTicketByQrcodeToken?client_id=$clientId&v=$UOP_API_V&request_id=${UUID.randomUUID()}&token=${enc(token)}"

    private const val QUARK_BIZ_STR = "S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0"
    private const val UC_BIZ_STR = "S:custom|C:titlebar_hover_2"
    private const val UC_PARAM_STR = "dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt"

    internal const val QUARK_UA = "Mozilla/5.0 (Linux; Android 14; Redmi K80) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    internal const val UC_UA = "Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    internal const val BAIDU_UA = "Mozilla/5.0 (Linux; Android 14; Redmi K80) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun longPollClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder().readTimeout(40, TimeUnit.SECONDS).build()

    // v1.3.6：与 ShareHttp.enc 同口径——空格编 %20（URLEncoder 原生编 + 会错意）。
    private fun enc(v: String): String = ShareHttp.enc(v)

    private fun getText(client: OkHttpClient, url: String, ua: String? = null, referer: String? = null): String? {
        val req = Request.Builder().url(url).apply {
            ua?.let { header("User-Agent", it) }
            referer?.let { header("Referer", it) }
        }.build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        }.getOrNull()
    }

    // ---------------- 夸克 / UC（同一套 uop CAS） ----------------

    /** 二维码内容：su.quark.cn 短链带 token（bundle 钉死的模板，client_id 532）。 */
    fun buildQuarkQrContent(token: String): String =
        "https://su.quark.cn/4_eMHBJ?token=${enc(token)}&client_id=532&ssb=weblogin" +
            "&uc_param_str=&uc_biz_str=${enc(QUARK_BIZ_STR)}"

    /** UC 二维码内容：broccoli 扫码页带 token（拼法按夸克同形，真机验证）。 */
    fun buildUcQrContent(token: String): String =
        "https://broccoli.uc.cn/apps/ZY5qIZiK/routes/1hbcQwLhH" +
            "?uc_param_str=${enc(UC_PARAM_STR)}" +
            "&uc_biz_str=${enc(UC_BIZ_STR)}" +
            "&token=${enc(token)}&client_id=381&ssb=weblogin"

    fun buildUopQrContent(platform: SharePlatform, token: String): String =
        if (platform == SharePlatform.UC) buildUcQrContent(token) else buildQuarkQrContent(token)

    /** 取 token：失败重试 4 次、间隔 300ms（bundle 同款口径）。先预热一次取 _UP_* 会话 Cookie。 */
    fun fetchQuarkToken(
        client: OkHttpClient = defaultClient(),
        uopBase: String = UOP_BASE,
        attempts: Int = 4,
        referer: String? = null,
        clientId: String = QUARK_CLIENT_ID,
    ): String? {
        getText(client, uopTokenUrl(uopBase, clientId), QUARK_UA, referer)
        repeat(attempts) { i ->
            val body = getText(client, uopTokenUrl(uopBase, clientId), QUARK_UA, referer)
            val token = body?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optJSONObject("data")?.optJSONObject("members")?.optString("token")
                ?.takeIf { it.isNotBlank() }
            if (token != null) return token
            if (i < attempts - 1) Thread.sleep(300)
        }
        return null
    }

    /** 轮询取 service_ticket：status 2000000 返回 ticket，未扫返回 null。 */
    fun pollQuarkTicket(
        client: OkHttpClient = defaultClient(),
        uopBase: String = UOP_BASE,
        token: String,
        referer: String? = null,
        clientId: String = QUARK_CLIENT_ID,
    ): String? {
        val body = getText(client, uopPollUrl(uopBase, clientId, token), QUARK_UA, referer)
            ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("status", -1) != 2000000) return null
        return json.optJSONObject("data")?.optJSONObject("members")?.optString("service_ticket")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * ticket 换 Cookie（bundle 实证：doAuth 调 bizHost/account/info?st=tmpST，
     * 成功看 body.success，Set-Cookie 落会话）。
     * 用调用方取 token 的同一个 jar（带 _UP_* 会话 Cookie）换取，带 lw=scan
     * 与业务站 Referer；成功判据放宽：__puus 或 __pus 任一在即可。
     * 成功返回整串 Cookie；失败返回 null。
     */
    fun exchangeUopTicket(
        client: OkHttpClient = defaultClient(),
        bizBase: String,
        ticket: String,
        ua: String,
        jar: CookieManager? = null,
        referer: String? = null,
    ): String? {
        val useJar = jar ?: CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val jarClient = client.newBuilder().cookieJar(JavaNetCookieJar(useJar)).build()
        val info = getText(jarClient, "$bizBase/account/info?st=${enc(ticket)}&lw=scan", ua, referer)
        val ok = info?.let { runCatching { JSONObject(it).optBoolean("success", false) }.getOrDefault(false) }
            ?: false
        if (!ok) {
            getText(jarClient, "$bizBase/?st=${enc(ticket)}&lw=scan", ua, referer)
        }
        val cookie = useJar.cookieStore.get(URI(bizBase))
            .joinToString("; ") { "${it.name}=${it.value}" }
        return cookie.takeIf { it.contains("__puus=") || it.contains("__pus=") }
    }

    // ---------------- 百度 ----------------

    data class BaiduQr(val sign: String, val imageUrl: String)

    /** 取二维码：errno 0 返回 sign＋imgurl（imgurl 无 scheme，补 https 直接用）。 */
    fun fetchBaiduQr(
        client: OkHttpClient = defaultClient(),
        passportBase: String = PASSPORT_BASE,
    ): BaiduQr? {
        val tt = System.currentTimeMillis()
        val url = "$passportBase/v2/api/getqrcode?lp=pc&qrloginfrom=pc&apiver=v3&tt=$tt" +
            "&tpl=netdisk&u=${enc("https://pan.baidu.com/")}"
        val body = getText(client, url, BAIDU_UA) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("errno", -1) != 0) return null
        val sign = json.optString("sign").takeIf { it.isNotBlank() } ?: return null
        val img = json.optString("imgurl").takeIf { it.isNotBlank() } ?: return null
        val imageUrl = if (img.startsWith("http")) img else "https://$img"
        return BaiduQr(sign, imageUrl)
    }

    data class BaiduLogin(val bduss: String, val stoken: String)

    /** 轮询 unicast（长轮询，BaiduPCS-Go 同款参数）：errno 0 且 v 里有 bduss/stoken 返回登录态，否则 null。 */
    fun pollBaiduUnicast(
        client: OkHttpClient = defaultClient(),
        passportBase: String = PASSPORT_BASE,
        sign: String,
    ): BaiduLogin? {
        val cb = "jQuery${(100000..999999).random()}_${System.currentTimeMillis()}"
        val url = "$passportBase/channel/unicast?channel_id=${enc(sign)}&callback=${enc(cb)}&apiver=v3&tpl=netdisk"
        val body = getText(longPollClient(client), url, BAIDU_UA) ?: return null
        val jsonText = body.substringAfter("(", "").substringBeforeLast(")", "").ifBlank { return null }
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        if (json.optInt("errno", -1) != 0) return null
        val v = json.optString("v").takeIf { it.isNotBlank() } ?: return null
        return parseBaiduV(v)
    }

    /** v 是形如 bduss=..&stoken=.. 的串；值可能 URL 编码过（如 %3D），按值逐个解码。 */
    fun parseBaiduV(v: String): BaiduLogin? {
        // 整串也可能被编码过（如 bduss%3D..%26stoken%3D..），先整串解码再按原文各试一次。
        val decoded = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        for (candidate in listOf(v, decoded)) {
            val map = candidate.split("&").mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) kv[0] to decodeValue(kv[1]) else null
            }.toMap()
            val bduss = map["bduss"].orEmpty()
            val stoken = map["stoken"].orEmpty()
            if (bduss.isNotBlank() && stoken.isNotBlank()) return BaiduLogin(bduss, stoken)
        }
        return null
    }

    /** 只解 %XX 转义，不动字面 +（BDUSS 里可能有 +，URLDecoder 会把它变空格）。 */
    private fun decodeValue(raw: String): String =
        runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
}

/** 扫码弹窗的状态机：Loading 取码中、Ready 待扫、Scanned 已扫待确认、Exchanging 换登录态中、Success 成功、Expired 过期、Error 失败。 */
sealed class QrState {
    data object Loading : QrState()
    data class Ready(val qrContent: String?, val qrImageUrl: String?) : QrState()
    data object Scanned : QrState()
    data object Exchanging : QrState()
    data object Success : QrState()
    data object Expired : QrState()
    data class Error(val message: String) : QrState()
}

/**
 * 一次扫码登录会话：生命周期跟弹窗走——弹窗打开 start()，关闭 cancel()。
 * 轮询 2 秒一次、总预算 120 秒，到期变 Expired（点刷新重取）。
 * scope 是弹窗的 rememberCoroutineScope，只在弹窗存活期用，关闭时
 * DisposableEffect 取消会话，不跨导航持有。
 */
class ShareQrSession(
    val platform: SharePlatform,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = ShareQrLogin.defaultClient(),
    private val uopBase: String = ShareQrLogin.UOP_BASE,
    private val quarkBizBase: String = ShareQrLogin.QUARK_BIZ,
    private val ucBizBase: String = ShareQrLogin.UC_BIZ,
    private val passportBase: String = ShareQrLogin.PASSPORT_BASE,
    private val pollIntervalMs: Long = 2000L,
    private val budgetMs: Long = 120_000L,
) {
    private val _state = MutableStateFlow<QrState>(QrState.Loading)
    val state: StateFlow<QrState> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            _state.value = QrState.Loading
            try {
                when (platform) {
                    SharePlatform.QUARK, SharePlatform.UC -> runUop()
                    SharePlatform.BAIDU -> runBaidu()
                    else -> _state.value = QrState.Error("这家盘没有扫码登录，用手动输入 Token")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.w("ShareQr", "${platform.displayName}扫码异常：${e.message}")
                _state.value = QrState.Error("网络异常，请点刷新重试")
            }
        }
    }

    fun refresh() = start()

    fun cancel() {
        job?.cancel()
    }

    private suspend fun runUop() {
        val bizBase = if (platform == SharePlatform.UC) ucBizBase else quarkBizBase
        val clientId = if (platform == SharePlatform.UC) ShareQrLogin.UC_CLIENT_ID else ShareQrLogin.QUARK_CLIENT_ID
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val jarClient = client.newBuilder().cookieJar(JavaNetCookieJar(jar)).build()
        val token = ShareQrLogin.fetchQuarkToken(jarClient, uopBase, referer = "$bizBase/", clientId = clientId)
        if (token == null) {
            _state.value = QrState.Error("取二维码失败，请点刷新重试")
            return
        }
        _state.value = QrState.Ready(ShareQrLogin.buildUopQrContent(platform, token), null)
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val ticket = ShareQrLogin.pollQuarkTicket(jarClient, uopBase, token, referer = "$bizBase/", clientId = clientId) ?: continue
            _state.value = QrState.Exchanging
            val ua = if (platform == SharePlatform.UC) ShareQrLogin.UC_UA else ShareQrLogin.QUARK_UA
            val cookie = ShareQrLogin.exchangeUopTicket(client, bizBase, ticket, ua, jar = jar, referer = "$bizBase/")
            if (cookie == null) {
                _state.value = QrState.Error("扫码成功，但换取登录态失败，请用手动输入 Cookie")
                return
            }
            val saved = ShareAuth.saveCookie(platform, cookie)
            if (!saved) {
                _state.value = QrState.Error("登录态保存失败，请重试")
                return
            }
            _state.value = QrState.Success
            return
        }
        _state.value = QrState.Expired
    }

    private suspend fun runBaidu() {
        val qr = ShareQrLogin.fetchBaiduQr(client, passportBase)
        if (qr == null) {
            _state.value = QrState.Error("取二维码失败，请点刷新重试")
            return
        }
        _state.value = QrState.Ready(null, qr.imageUrl)
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val login = ShareQrLogin.pollBaiduUnicast(client, passportBase, qr.sign) ?: continue
            _state.value = QrState.Exchanging
            val cookie = "BDUSS=${login.bduss}; STOKEN=${login.stoken}"
            if (!ShareAuth.saveCookie(platform, cookie)) {
                _state.value = QrState.Error("登录态保存失败，请重试")
                return
            }
            _state.value = QrState.Success
            return
        }
        _state.value = QrState.Expired
    }
}
