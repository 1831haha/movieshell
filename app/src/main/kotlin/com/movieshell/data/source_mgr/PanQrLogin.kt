package com.movieshell.data.source_mgr

import com.movieshell.BuildConfig
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * v1.1.25：六家网盘扫码登录引擎（不再嵌 WebView 登录页）。
 *
 * 链路证据（调研报告 research_notes/pan-qr-login-v1.1.25-20261111/report.md）：
 * - 夸克：官方 bundle 实证——GET uop getTokenForQrcodeLogin 取 token，
 *   二维码内容拼 su.quark.cn/4_eMHBJ（180x180 本地画），轮询
 *   getServiceTicketByQrcodeToken（2 秒一次，status 2000000 取
 *   service_ticket），doAuth 调 bizHost/account/info?st=tmpST 换会话，
 *   Set-Cookie 落 __puus 等；本文件按此实现，换取失败明确报错。
 * - 百度：getqrcode 已实测通（返回 imgurl＋sign，图片直接用），轮询
 *   channel/unicast 按 BaiduPCS-Go 参数接（channel_id=sign、callback、
 *   apiver=v3、tpl=netdisk），沙箱两次 000 未通，真机复测。
 * - UC：与夸克同一套 uop CAS，client_id 换 381、扫码页换 broccoli
 *   域名；取 token 接口返回结构与夸克一致已实测，扫码页 URL 拼接是
 *   推断，真机扫码验证。
 * - 阿里：aliyunpan 开源实现全链——POST oauth/authorize/qrcode 取
 *   qrCodeUrl → GET status 四态 → POST oauth/access_token 换 token；
 *   凭证走构建期注入。2026-11-11 用户定先不做扫码：runAliyun 留着
 *   以后用，会话层直接走手动输入分支，不调它。
 * - 迅雷/光鸭：取二维码端点未钉死（光鸭 4 个候选路径全 404），本版
 *   只保留手动填 token 入口顶着，不阻塞前四家。
 *
 * 全部自写代码，只读协议思路；不拷贝任何第三方源码（AGPL 红线）。
 * Cookie/token 值绝不写进日志。
 */
object PanQrLogin {

    private const val TAG = "PanQr"

    internal const val UOP_BASE = "https://uop.quark.cn"
    internal const val QUARK_BIZ = "https://pan.quark.cn"
    internal const val UC_BIZ = "https://drive.uc.cn"
    internal const val PASSPORT_BASE = "https://passport.baidu.com"
    internal const val OPENAPI_BASE = "https://openapi.alipan.com"

    // uop 默认参数（官方 bundle 实证：axios 实例 bA 的默认 params {client_id:532, v:"1.2"}，
    // 请求拦截器每次补一个全新 request_id；withCredentials 带 Cookie）。
    // v1.1.29：之前取 token/轮询一个参数都没带，服务端按默认客户端签发 token，
    // 夸克浏览器拿它去确认（client_id 532）时对不上，就报“登录请求已过期”——
    // 码、UA、Referer、Cookie 都不是根因，缺的是这三个参数。
    internal const val QUARK_CLIENT_ID = "532"
    internal const val UC_CLIENT_ID = "381"
    internal const val UOP_API_V = "1.2"

    /** uop 取 token 地址：每次请求一个全新 request_id（官方拦截器口径）。 */
    private fun uopTokenUrl(uopBase: String, clientId: String): String =
        "$uopBase/cas/ajax/getTokenForQrcodeLogin?client_id=$clientId&v=$UOP_API_V&request_id=${UUID.randomUUID()}"

    /** uop 轮询地址：同上，另带 token。 */
    private fun uopPollUrl(uopBase: String, clientId: String, token: String): String =
        "$uopBase/cas/ajax/getServiceTicketByQrcodeToken?client_id=$clientId&v=$UOP_API_V&request_id=${UUID.randomUUID()}&token=${enc(token)}"
    // 二维码内容里的 uc_biz_str（bundle/UC chunk 原值，抽常量避免模板内嵌引号）。
    private const val QUARK_BIZ_STR = "S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0"
    private const val UC_BIZ_STR = "S:custom|C:titlebar_hover_2"
    private const val UC_PARAM_STR = "dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt"

    // UA 照抄 PanShareCheck 的三家（仓库现有写法，不新编）；internal 供会话层用。
    // v1.1.27：夸克取 token 必须用手机 UA——之前是 Windows 桌面 Chrome UA，
    // 发出去的 token 是按桌面网页签发的，用户用夸克浏览器扫码确认时被拒
    // （OK影视同款码能过、咱的不能过，码一模一样、扫码 App 也是同一个，
    // 只剩取 token 的请求不一样）。改成与手机浏览器同款移动 UA。
    internal const val QUARK_UA = "Mozilla/5.0 (Linux; Android 14; Redmi K80) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    internal const val UC_UA = "Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    internal const val BAIDU_UA = "Mozilla/5.0 (Linux; Android 14; Redmi K80) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 百度 unicast 是长轮询：读超时放宽到 40 秒，等服务端自己回。 */
    private fun longPollClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder().readTimeout(40, TimeUnit.SECONDS).build()

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

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

    private fun postJsonText(client: OkHttpClient, url: String, bodyJson: String, ua: String? = null): String? {
        val req = Request.Builder().url(url)
            .post(bodyJson.toRequestBody(JSON))
            .apply { ua?.let { header("User-Agent", it) } }.build()
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

    /**
     * UC 二维码内容：broccoli 扫码页带 token（推断：chunk 里只钉死了扫码页
     * 模板与 client_id 381，token/client_id/ssb 拼法按夸克同形，真机验证）。
     */
    fun buildUcQrContent(token: String): String =
        "https://broccoli.uc.cn/apps/ZY5qIZiK/routes/1hbcQwLhH" +
            "?uc_param_str=${enc(UC_PARAM_STR)}" +
            "&uc_biz_str=${enc(UC_BIZ_STR)}" +
            "&token=${enc(token)}&client_id=381&ssb=weblogin"

    fun buildUopQrContent(provider: PanProvider, token: String): String =
        if (provider == PanProvider.UC) buildUcQrContent(token) else buildQuarkQrContent(token)

    /** 取 token：失败重试 4 次、间隔 300ms（bundle 同款口径）。
     * v1.1.27：先预热一次取 _UP_* 会话 Cookie（调用方传 jarClient 才会
     * 存下来），正式取 token 时带上业务站 Referer，与手机浏览器同款会话。
     * v1.1.29：每次请求带 client_id＋v＋全新 request_id（官方 uop 默认参数）。 */
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

    /**
     * 轮询取 service_ticket：status 2000000 返回 ticket，未扫返回 null；
     * 网络异常也返回 null（外层继续轮询，不因一次抖动杀掉整个会话）。
     */
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
     * v1.1.30：①用调用方取 token 的同一个 jar（带 _UP_* 会话 Cookie）换取——
     *   之前每次新建空 jar，服务端认不出 ticket 是哪个会话的就拒（1.1.29 真机：
     *   扫码已成功、换取失败）；②带 lw=scan（QuarkPan 同款；官方 doAuth 也认
     *   这个参数）与业务站 Referer；③成功判据放宽：__puus 或 __pus 任一在即
     *   可（扫码登录只落 __pus 也算通，QuarkPan 拿到的就是 __pus/__kp/__kps/__uid）。
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
            // 兜底：根路径 ?st= 再收一次（account/info 若不存在时靠它落 Cookie）。
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

    /**
     * 轮询 unicast（长轮询，BaiduPCS-Go 同款参数；沙箱两次 000 未通，
     * 真机复测）：errno 0 且 v 里有 bduss/stoken 返回登录态，否则 null。
     */
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

    /** v 是形如 bduss=..&stoken=.. 的串；先按原文解析，找不到再 URL 解码后解析。 */
    fun parseBaiduV(v: String): BaiduLogin? {
        val decoded = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        for (candidate in listOf(v, decoded)) {
            val map = candidate.split("&").mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) kv[0] to kv[1] else null
            }.toMap()
            val bduss = map["bduss"].orEmpty()
            val stoken = map["stoken"].orEmpty()
            if (bduss.isNotBlank() && stoken.isNotBlank()) return BaiduLogin(bduss, stoken)
        }
        return null
    }

    // ---------------- 阿里云盘 ----------------

    data class AliyunQr(val qrCodeUrl: String)

    data class AliyunTokens(val accessToken: String, val refreshToken: String, val expiresIn: Long)

    enum class AliyunStatus { WAIT, SCANNED, SUCCESS, EXPIRED, UNKNOWN }

    data class AliyunPoll(val status: AliyunStatus, val authCode: String?)

    /** 取二维码：POST oauth/authorize/qrcode，返回 qrCodeUrl（本地按此画码）。 */
    fun fetchAliyunQr(
        client: OkHttpClient = defaultClient(),
        openApiBase: String = OPENAPI_BASE,
        clientId: String,
        clientSecret: String,
    ): AliyunQr? {
        val bodyJson = JSONObject()
            .put("client_id", clientId)
            .put("client_secret", clientSecret)
            .put("scopes", JSONArray(listOf("user:base", "file:all:read", "file:all:write")))
            .put("width", 348)
            .put("height", 348)
            .toString()
        val resp = postJsonText(client, "$openApiBase/oauth/authorize/qrcode", bodyJson, QUARK_UA)
            ?: return null
        val json = runCatching { JSONObject(resp) }.getOrNull() ?: return null
        val url = json.optString("qrCodeUrl").takeIf { it.isNotBlank() } ?: return null
        return AliyunQr(url)
    }

    /** 轮询状态：WaitLogin/ScanSuccess/LoginSuccess/QRCodeExpired 四态。 */
    fun pollAliyunStatus(
        client: OkHttpClient = defaultClient(),
        qrCodeUrl: String,
    ): AliyunPoll {
        val body = getText(client, "$qrCodeUrl/status", QUARK_UA)
            ?: return AliyunPoll(AliyunStatus.UNKNOWN, null)
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return AliyunPoll(AliyunStatus.UNKNOWN, null)
        val status = when (json.optString("status")) {
            "WaitLogin" -> AliyunStatus.WAIT
            "ScanSuccess" -> AliyunStatus.SCANNED
            "LoginSuccess" -> AliyunStatus.SUCCESS
            "QRCodeExpired" -> AliyunStatus.EXPIRED
            else -> AliyunStatus.UNKNOWN
        }
        return AliyunPoll(status, json.optString("authCode").takeIf { it.isNotBlank() })
    }

    /** authCode 换 token：POST oauth/access_token（authorization_code）。 */
    fun exchangeAliyunCode(
        client: OkHttpClient = defaultClient(),
        openApiBase: String = OPENAPI_BASE,
        clientId: String,
        clientSecret: String,
        authCode: String,
    ): AliyunTokens? {
        val bodyJson = JSONObject()
            .put("code", authCode)
            .put("grant_type", "authorization_code")
            .put("client_id", clientId)
            .put("client_secret", clientSecret)
            .toString()
        val resp = postJsonText(client, "$openApiBase/oauth/access_token", bodyJson, QUARK_UA)
            ?: return null
        val json = runCatching { JSONObject(resp) }.getOrNull() ?: return null
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        return AliyunTokens(access, json.optString("refresh_token").orEmpty(), json.optLong("expires_in", 0))
    }

    /**
     * 换 token 后顺手验一次：GET api.alipan.com/v2/user/get 带 Bearer，
     * 200 即确认；不通也不否决（token 是 OAuth 服务端刚发的，本身即登录凭证），
     * 只记一条日志。端点按 aliyunpan 同款，真机复测。
     */
    fun validateAliyunToken(
        client: OkHttpClient = defaultClient(),
        accessToken: String,
    ): Boolean {
        val req = Request.Builder().url("https://api.alipan.com/v2/user/get")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", QUARK_UA).build()
        return runCatching {
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
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
 * 注意：这里的 scope 是弹窗的 rememberCoroutineScope，只在弹窗存活期
 * 用，关闭时 DisposableEffect 取消会话，不跨导航持有（HomeScreen 退避
 * 重试持组合 scope 跨页的教训，见 HomeScreen 退避分支注释）。
 */
class PanQrSession(
    val provider: PanProvider,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = PanQrLogin.defaultClient(),
    private val uopBase: String = PanQrLogin.UOP_BASE,
    private val quarkBizBase: String = PanQrLogin.QUARK_BIZ,
    private val ucBizBase: String = PanQrLogin.UC_BIZ,
    private val passportBase: String = PanQrLogin.PASSPORT_BASE,
    private val openApiBase: String = PanQrLogin.OPENAPI_BASE,
    private val aliyunClientId: String = BuildConfig.ALIYUN_APP_ID,
    private val aliyunClientSecret: String = BuildConfig.ALIYUN_APP_SECRET,
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
                when (provider) {
                    PanProvider.QUARK, PanProvider.UC -> runUop()
                    PanProvider.BAIDU -> runBaidu()
                    PanProvider.ALIYUN -> runAliyun()
                    PanProvider.GUANGYA, PanProvider.XUNLEI ->
                        _state.value = QrState.Error("扫码登录正在对接，暂用手动输入 Token")
                    // 2026-11-11 用户定阿里先不做扫码：会话不走 runAliyun（函数留着，
                    // 以后注册完凭证恢复只要把这一支删掉），弹窗直接展开手动输入。
                    PanProvider.ALIYUN ->
                        _state.value = QrState.Error("扫码登录暂不开通，请用手动输入 Token")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.w("PanQr", "${provider.displayName}扫码异常：${e.message}")
                _state.value = QrState.Error("网络异常，请点刷新重试")
            }
        }
    }

    fun refresh() = start()

    fun cancel() {
        job?.cancel()
    }

    private suspend fun runUop() {
        // v1.1.27：取 token 与轮询共用一个 Cookie 会话（uop 下发的 _UP_*），
        // 并带上业务站 Referer，尽量贴近手机浏览器的真实请求（裸请求＋桌面
        // UA 取到的 token 在夸克浏览器里确认会被拒）。
        val bizBase = if (provider == PanProvider.UC) ucBizBase else quarkBizBase
        val clientId = if (provider == PanProvider.UC) PanQrLogin.UC_CLIENT_ID else PanQrLogin.QUARK_CLIENT_ID
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val jarClient = client.newBuilder().cookieJar(JavaNetCookieJar(jar)).build()
        val token = PanQrLogin.fetchQuarkToken(jarClient, uopBase, referer = "$bizBase/", clientId = clientId)
        if (token == null) {
            _state.value = QrState.Error("取二维码失败，请点刷新重试")
            return
        }
        _state.value = QrState.Ready(PanQrLogin.buildUopQrContent(provider, token), null)
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val ticket = PanQrLogin.pollQuarkTicket(jarClient, uopBase, token, referer = "$bizBase/", clientId = clientId) ?: continue
            _state.value = QrState.Exchanging
            val ua = if (provider == PanProvider.UC) PanQrLogin.UC_UA else PanQrLogin.QUARK_UA
            val cookie = PanQrLogin.exchangeUopTicket(client, bizBase, ticket, ua, jar = jar, referer = "$bizBase/")
            if (cookie == null) {
                _state.value = QrState.Error("扫码成功，但换取登录态失败，请用手动输入 Cookie")
                return
            }
            val saved = if (provider == PanProvider.UC) UcAuthStore.saveCookie(cookie)
            else QuarkAuthStore.saveCookie(cookie)
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
        val qr = PanQrLogin.fetchBaiduQr(client, passportBase)
        if (qr == null) {
            _state.value = QrState.Error("取二维码失败，请点刷新重试")
            return
        }
        _state.value = QrState.Ready(null, qr.imageUrl)
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val login = PanQrLogin.pollBaiduUnicast(client, passportBase, qr.sign) ?: continue
            _state.value = QrState.Exchanging
            val cookie = "BDUSS=${login.bduss}; STOKEN=${login.stoken}"
            if (!BaiduAuthStore.saveCookie(cookie)) {
                _state.value = QrState.Error("登录态保存失败，请重试")
                return
            }
            _state.value = QrState.Success
            return
        }
        _state.value = QrState.Expired
    }

    private suspend fun runAliyun() {
        if (aliyunClientId.isBlank() || aliyunClientSecret.isBlank()) {
            _state.value = QrState.Error("应用凭证未配置，等注册完成")
            return
        }
        val qr = PanQrLogin.fetchAliyunQr(client, openApiBase, aliyunClientId, aliyunClientSecret)
        if (qr == null) {
            _state.value = QrState.Error("取二维码失败，请点刷新重试")
            return
        }
        _state.value = QrState.Ready(qr.qrCodeUrl, null)
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val poll = PanQrLogin.pollAliyunStatus(client, qr.qrCodeUrl)
            when (poll.status) {
                PanQrLogin.AliyunStatus.SCANNED -> _state.value = QrState.Scanned
                PanQrLogin.AliyunStatus.SUCCESS -> {
                    val authCode = poll.authCode
                    if (authCode == null) {
                        _state.value = QrState.Error("登录成功但没拿到授权码，请点刷新重试")
                        return
                    }
                    _state.value = QrState.Exchanging
                    val tokens = PanQrLogin.exchangeAliyunCode(
                        client, openApiBase, aliyunClientId, aliyunClientSecret, authCode,
                    )
                    if (tokens == null) {
                        _state.value = QrState.Error("换取 token 失败，请点刷新重试")
                        return
                    }
                    if (!PanQrLogin.validateAliyunToken(client, tokens.accessToken)) {
                        LogBus.w("PanQr", "阿里 token 校验未通过（user/get 非 200），仍按已登录存")
                    }
                    if (!AliyunAuthStore.saveTokens(tokens)) {
                        _state.value = QrState.Error("登录态保存失败，请重试")
                        return
                    }
                    _state.value = QrState.Success
                    return
                }
                    PanQrLogin.AliyunStatus.EXPIRED -> {
                        _state.value = QrState.Expired
                        return
                    }
                    else -> Unit
                }
        }
        _state.value = QrState.Expired
    }
}
