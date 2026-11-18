package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.3.5：夸克 / UC 分享解析（Kotlin 直写，逻辑照搬旧 quark.py / uc.py /
 * PanSaveApi / PanShareCheck，两家接口同名同形，只差 host 与 pr）。
 *
 * 链路：token 拿 stoken → detail 列目录 → 取直链三档按顺序走，拿到就停：
 * 1. 直取（share 侧 file/download）——小文件游客直接通；
 * 2. 转存原画（登录态）：save → 轮询 task → 自己盘 file/download，
 *    转存目录名叫 TV（找到先清空，旧代码同款口径）；
 * 3. 转码预览（登录态，转存成功但直取失败时）：file/v2/play 取 m3u8。
 * 游客兜底：video_preview 拿 2 分钟预览 m3u8（2026-11-17/18 沙箱实测，
 * free_time=120，m3u8 仅 121 秒），播之前 UI 必须明说这是预览。
 *
 * 已实测（2026-11-10 沙箱、2026-11-17/18 沙箱复测）：
 * - token、detail（drive-pc.quark.cn 与 drive.quark.cn 双 host 都通）；
 * - video_preview 通，play_info.url 是可播 m3u8；
 * - 游客直取只放行约 50MB 以内，大文件回 23018，必须登录；
 * - 直链回源必须带 file/download 响应下发的 __pugs Cookie，裸 URL 回 403；
 * - fidToken 与 stoken 是同一会话配对的，混用新 stoken 报 41020。
 * 登录后全程（转存/转码）沙箱验不了（无登录 Cookie），单测用
 * MockWebServer 覆盖，真机登录后待验。
 *
 * v1.3.8（2026-11-18 真机故障复盘）：
 * - 播放 Referer 按播放地址的 host 家族取：预览/转码 m3u8 在 video-play-*
 *   主机上，CDN 只认同族 drive 域名作 Referer（drive.uc.cn 的地址配
 *   https://drive.uc.cn/，drive.quark.cn 的配 https://drive.quark.cn/）；
 *   之前一律发 pan.quark.cn，m3u8 与分片双双 403，播放器报
 *   ERROR_CODE_IO_BAD_HTTP_STATUS。沙箱实测：换 Referer 后 m3u8 与首个
 *   分片都回 200。直链（dl-*）仍用配置 Referer。
 * - video_preview 与 file/v2/play 响应下发的 __pugs 不再丢掉，透传给播放器。
 * - downloadSaved 兼容 data 的两种形态：对象数组取 download_url，
 *   字符串数组取字符串本身。
 * - 转存轮询 3 次×1 秒根本不够大文件服务端复制（真机 4 次超时）：
 *   首轮 30 次×2 秒（60 秒），同一 resolve 内不再重建任务，超时后对
 *   同一 task_id 再轮询 15 次×2 秒；TV 目录里已有同名同大小文件时
 *   直接复用，不清空重转。
 *
 * v1.3.13（2026-11-18 真机故障复盘）：转码 m3u8 仍播不了——Media3 与
 * libmpv 双双 412，黑屏。根因：夸克缺 __pugs 时 CDN 回 412（研究笔记
 * 云X口径；沙箱拿真机日志里的同一条转码地址复现：无 Cookie 时换什么
 * Referer/UA 都是 412）。1.3.8 只认 file/v2/play 响应里下发的 __pugs，
 * 该响应未必下发，拿不到就等于没带 Cookie。修法：播放 Cookie 改由
 * playbackCookie 拼——响应下发的 __pugs 优先，其次登录 Cookie 里的
 * __pugs，再其次整串会话 Cookie 一并带上（同属夸克自家域名）。
 * Cookie 值绝不写日志，只记有无。
 *
 * v1.3.14（2026-11-18 真机故障复盘）：1.3.13 仍 412——日志里“播放头
 * Cookie=已带”，但带的只是登录会话 Cookie（登录已 5 小时，__pugs
 * 早过期），转码接口本身又不下发 __pugs。真凶：saveFlow 里 file/download
 * 响应下发的新鲜 __pugs 已被拿到（downloadSaved 返回的 pugs），走到
 * transcode 那一步被新 SaveOutcome(bestUrl, resp.pugs, …) 整个丢掉。
 * 修法：转码结果保留直取那一步的 pugs（keepPugs）；日志再记 __pugs
 * 有无，下轮真机一眼定性。
 */
object QuarkUcApi {

    private const val TAG = "ShareQuarkUc"

    /** 游客直取约 50MB 封顶；大于它且已登录时优先走转存。 */
    private const val LARGE_SIZE = 50L * 1024 * 1024

    private const val QUARK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val UC_UA = "Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    data class Config(
        val apiBase: String,
        val pr: String,
        val ua: String,
        val referer: String,
        val fidsAsList: Boolean,
    )

    val QUARK = Config(
        apiBase = "https://drive-pc.quark.cn/1/clouddrive",
        pr = "pr=ucpro&fr=pc",
        ua = QUARK_UA,
        referer = "https://pan.quark.cn/",
        fidsAsList = false,
    )

    val UC = Config(
        apiBase = "https://pc-api.uc.cn/1/clouddrive",
        pr = "pr=UCBrowser&fr=pc",
        ua = UC_UA,
        referer = "https://drive.uc.cn/",
        fidsAsList = true,
    )

    fun configOf(platform: SharePlatform): Config = when (platform) {
        SharePlatform.QUARK -> QUARK
        SharePlatform.UC -> UC
        else -> QUARK
    }

    enum class Check { OK, DEAD, NEED_LOGIN, LOGIN_EXPIRED, UNKNOWN }

    /** 播前检测：token 一步定生死（照搬 PanShareCheck.checkQuarkUc）。 */
    fun check(
        platform: SharePlatform,
        shareUrl: String,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): Check {
        val cfg = configOf(platform)
        val cookie = ShareAuth.getCookie(platform)
        val (pwdId, passcode) = ShareLink.parse(shareUrl) ?: return Check.UNKNOWN
        val guest = postToken(client, cfg, pwdId, passcode, "")
        return when (guest) {
            0 -> Check.OK
            41027, 41011 -> Check.DEAD
            31001 -> {
                if (cookie.isBlank()) return Check.NEED_LOGIN
                when (postToken(client, cfg, pwdId, passcode, cookie)) {
                    0 -> Check.OK
                    31001 -> Check.LOGIN_EXPIRED
                    41027, 41011 -> Check.DEAD
                    else -> Check.UNKNOWN
                }
            }
            else -> Check.UNKNOWN
        }
    }

    /** token 一步返回的 code；网络失败返回 null。 */
    private fun postToken(
        client: OkHttpClient,
        cfg: Config,
        pwdId: String,
        passcode: String,
        cookie: String,
    ): Int? {
        val body = JSONObject().put("pwd_id", pwdId).put("passcode", passcode)
        val resp = ShareHttp.postJson(client, "${cfg.apiBase}/share/sharepage/token?${cfg.pr}", cookie, cfg.ua, cfg.referer, body)
        return resp.json?.optInt("code", -1)
    }

    /** token 拿 stoken 与标题；失败返回空串。 */
    fun fetchToken(
        client: OkHttpClient,
        cfg: Config,
        pwdId: String,
        passcode: String,
        cookie: String = "",
    ): Pair<String, String> {
        val body = JSONObject().put("pwd_id", pwdId).put("passcode", passcode)
        val resp = ShareHttp.postJson(client, "${cfg.apiBase}/share/sharepage/token?${cfg.pr}", cookie, cfg.ua, cfg.referer, body)
        val json = resp.json ?: return "" to ""
        if (json.optInt("code", -1) != 0) return "" to ""
        val data = json.optJSONObject("data") ?: return "" to ""
        return data.optString("stoken").orEmpty() to data.optString("title").orEmpty()
    }

    /** 一页 detail，解析成 ShareFile（文件夹在前，服务端原序）。 */
    fun detailPage(
        client: OkHttpClient,
        cfg: Config,
        pwdId: String,
        stoken: String,
        pdirFid: String,
        page: Int,
    ): List<ShareFile> {
        // v1.3.6：查询串里的值一律编码——stoken 常含 +/=，不编码会被截断，
        // 服务端回 14001“非法token”，目录报空。
        val url = "${cfg.apiBase}/share/sharepage/detail?ver=2&pwd_id=${ShareHttp.enc(pwdId)}" +
            "&stoken=${ShareHttp.enc(stoken)}" +
            "&pdir_fid=${ShareHttp.enc(pdirFid)}&force=0&_page=$page&_size=50" +
            "&_fetch_banner=${if (pdirFid == "0") 1 else 0}" +
            "&_fetch_share=${if (pdirFid == "0") 1 else 0}" +
            "&fetch_relate_conversation=${if (pdirFid == "0") 1 else 0}" +
            "&_fetch_total=1&_sort="
        val resp = ShareHttp.getJson(client, url, "", cfg.ua, cfg.referer)
        val json = resp.json ?: return emptyList()
        if (json.optInt("code", -1) != 0) return emptyList()
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<ShareFile>(list.length())
        for (i in 0 until list.length()) {
            val f = list.optJSONObject(i) ?: continue
            val isDir = f.optBoolean("dir", f.optInt("file_type", 1) == 0)
            out.add(
                ShareFile(
                    fid = f.optString("fid").orEmpty(),
                    name = f.optString("file_name").orEmpty(),
                    size = f.optLong("size", 0L),
                    isDir = isDir,
                    dirKey = f.optString("fid").orEmpty(),
                    fidToken = f.optString("share_fid_token").orEmpty(),
                    durationSec = f.optLong("duration", 0L),
                ),
            )
        }
        return out
    }

    /** 列一层目录（根目录 dirFid="0"）。一页 50 条，最多翻 4 页。 */
    fun listDir(
        platform: SharePlatform,
        shareUrl: String,
        dirFid: String,
        client: OkHttpClient = ShareHttp.defaultClient(),
        // v1.3.6：cfg 默认按平台取，单测可传 MockWebServer 的 cfg。
        cfg: Config = configOf(platform),
    ): ShareResult<ShareListing> {
        val (pwdId, passcode) = ShareLink.parse(shareUrl)
            ?: return ShareResult.Err("这个链接认不出来，换个分享链接再试")
        val (stoken, title) = fetchToken(client, cfg, pwdId, passcode)
        if (stoken.isBlank()) {
            return ShareResult.Err("${platform.displayName}分享打不开：可能已失效、要提取码，或接口变了", dead = false)
        }
        val files = ArrayList<ShareFile>()
        for (page in 1..4) {
            val batch = detailPage(client, cfg, pwdId, stoken, dirFid, page)
            files.addAll(batch)
            if (batch.size < 50) break
        }
        if (files.isEmpty()) {
            return ShareResult.Err("这个分享里是空的，或文件夹里没有文件")
        }
        return ShareResult.Ok(
            ShareListing(
                platform = platform,
                shareUrl = shareUrl,
                title = title.ifBlank { files.firstOrNull()?.name.orEmpty() },
                files = files,
                session = ShareSession.QuarkUc(pwdId, stoken, passcode),
            ),
        )
    }

    /** 直取一步：返回（直链，pugs，错误码）。 */
    private fun download(
        client: OkHttpClient,
        cfg: Config,
        session: ShareSession.QuarkUc,
        file: ShareFile,
        cookie: String,
    ): Triple<String, String, Int> {
        val body = JSONObject()
        if (cfg.fidsAsList) body.put("fids", JSONArray().put(file.fid))
        else body.put("fids", file.fid)
        body.put("fids_token", JSONArray().put(file.fidToken))
        body.put("pwd_id", session.pwdId)
        body.put("stoken", session.stoken)
        body.put("speedup_session", "")
        body.put("token", "")
        val resp = ShareHttp.postJson(client, "${cfg.apiBase}/file/download?${cfg.pr}", cookie, cfg.ua, cfg.referer, body)
        val json = resp.json ?: return Triple("", "", -1)
        val code = json.optInt("code", -1)
        if (code != 0) return Triple("", resp.pugs, code)
        val data = json.opt("data")
        val url = when (data) {
            is JSONArray -> if (data.length() > 0) data.optJSONObject(0)?.optString("download_url").orEmpty() else ""
            is JSONObject -> data.optString("download_url").orEmpty()
            else -> ""
        }
        return Triple(url, resp.pugs, 0)
    }

    /** 游客 2 分钟预览 m3u8（sharepage/video_preview，2026-11-17 沙箱实测）。返回（m3u8，pugs）。 */
    internal fun videoPreview(
        client: OkHttpClient,
        cfg: Config,
        session: ShareSession.QuarkUc,
        file: ShareFile,
    ): Pair<String, String> {
        // v1.3.6：fid_token 与 stoken 常含 +/=，查询串里的值一律编码。
        val url = "${cfg.apiBase}/share/sharepage/video_preview?fid=${ShareHttp.enc(file.fid)}" +
            "&fid_token=${ShareHttp.enc(file.fidToken)}&pwd_id=${ShareHttp.enc(session.pwdId)}" +
            "&stoken=${ShareHttp.enc(session.stoken)}&is_share_preview=true"
        val resp = ShareHttp.getJson(
            client, url, "", cfg.ua, cfg.referer,
            extraHeaders = mapOf("x-clouddrive-st" to session.stoken),
        )
        val json = resp.json ?: return "" to ""
        if (json.optInt("code", -1) != 0) return "" to ""
        val data = json.optJSONObject("data") ?: return "" to ""
        val playInfo = data.optJSONObject("play_info")
        val m3u8 = playInfo?.optString("url").orEmpty()
        // v1.3.8：响应下发的 __pugs 别丢，播放时一并带上（直链裸 URL 回 403 的同款规矩）。
        if (m3u8.isNotBlank()) return m3u8 to resp.pugs
        return data.optString("preview_url").orEmpty() to resp.pugs
    }

    /**
     * v1.3.8：播放 Referer 按播放地址的 host 家族取——预览/转码 m3u8 在
     * video-play-* 主机上，CDN 只认同族 drive 域名作 Referer；之前一律发
     * pan.quark.cn，m3u8 与分片双双 403（2026-11-18 沙箱实测，换 Referer
     * 后 m3u8 与首个分片都回 200）。直链（dl-*）仍用配置 Referer。
     */
    internal fun playRefererFor(url: String, cfg: Config): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        if ("video-play" in host) {
            return when {
                host.endsWith(".drive.uc.cn") -> "https://drive.uc.cn/"
                host.endsWith(".drive.quark.cn") -> "https://drive.quark.cn/"
                else -> cfg.referer
            }
        }
        return cfg.referer
    }

    /**
     * v1.3.13：拼播放用的 Cookie 头值。响应下发的 __pugs 优先；没有就取
     * 登录 Cookie 里带的 __pugs；再没有就把整串会话 Cookie 一并带上
     * （同属夸克自家域名，会话绑定也一并满足）。两者都空返回 ""。
     * 只拼值，不记日志——调用方只记有无，绝不记值。
     */
    internal fun playbackCookie(apiPugs: String, sessionCookie: String): String {
        val pairs = sessionCookie.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && '=' in it }
        var sessionPugs = ""
        val rest = ArrayList<String>(pairs.size)
        for (pair in pairs) {
            val key = pair.substringBefore('=').trim()
            if (key.equals("__pugs", ignoreCase = true)) {
                if (sessionPugs.isEmpty()) sessionPugs = pair.substringAfter('=', "").trim()
            } else {
                rest.add(pair)
            }
        }
        val pugs = apiPugs.trim().ifBlank { sessionPugs }
        val out = ArrayList<String>(rest.size + 1)
        if (pugs.isNotEmpty()) out.add("__pugs=$pugs")
        out.addAll(rest)
        return out.joinToString("; ")
    }

    /**
     * v1.3.14：转码结果自带的 Cookie 为空时，用直取那一步拿到的补回来——
     * file/download 响应下发的新鲜 __pugs 不能丢在 transcode 的半路上。
     * 已有值的不动；两边都空返回原样。
     */
    internal fun keepPugs(outcome: SaveOutcome, fallbackCookie: String): SaveOutcome =
        if (outcome.cookie.isNotBlank()) outcome else outcome.copy(cookie = fallbackCookie)

    private fun playHeaders(cfg: Config, pugs: String, url: String, sessionCookie: String = ""): Map<String, String> {
        val headers = mutableMapOf("Referer" to playRefererFor(url, cfg), "User-Agent" to cfg.ua)
        val cookie = playbackCookie(pugs, sessionCookie)
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        // v1.3.13：只记有无，绝不记 Cookie 值（字符串模板里不嵌套引号，
        // 否则自查脚本的去字符串会把 Cookie 当成漏 import 的符号）。
        // v1.3.14：再记 __pugs 有无——光“已带”定不了性。
        val hasCookie = headers.containsKey("Cookie")
        val hasPugs = cookie.split(';').any { it.trim().startsWith("__pugs=", ignoreCase = true) }
        LogBus.d(TAG, "播放头 Cookie=" + (if (hasCookie) "已带" else "无") + " __pugs=" + (if (hasPugs) "有" else "无"))
        return headers
    }

    /**
     * 取一集的播放地址。三档 + 游客预览兜底，按顺序走，拿到就停。
     * session 必须用列目录那一份（stoken 与 fidToken 配对，见 ShareSession 注释）。
     */
    fun resolve(
        platform: SharePlatform,
        listing: ShareListing,
        file: ShareFile,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<PlayLink> {
        val cfg = configOf(platform)
        val session = listing.session as? ShareSession.QuarkUc
            ?: return ShareResult.Err("会话丢了，重新打开这个分享再试")
        val cookie = ShareAuth.getCookie(platform)
        val name = platform.displayName
        var saved = SaveOutcome("", "", "", "")
        if (cookie.isNotBlank() && file.size > LARGE_SIZE) {
            saved = saveFlowWithTranscode(client, cfg, session, file, cookie)
            if (saved.url.isNotBlank()) {
                return ShareResult.Ok(PlayLink(saved.url, playHeaders(cfg, saved.cookie, saved.url, cookie)))
            }
        }
        val (url, pugs, code) = download(client, cfg, session, file, "")
        if (url.isNotBlank()) {
            return ShareResult.Ok(PlayLink(url, playHeaders(cfg, pugs, url, cookie)))
        }
        if (code == 31001) {
            ShareAuth.markExpired(platform)
            return ShareResult.Err("${name}登录已过期，去登录页重登再试", needLogin = true)
        }
        if (code == 23018 || code == 31001) {
            if (cookie.isNotBlank()) {
                val (url2, pugs2, code2) = download(client, cfg, session, file, cookie)
                if (url2.isNotBlank()) {
                    return ShareResult.Ok(PlayLink(url2, playHeaders(cfg, pugs2, url2, cookie)))
                }
                if (code2 == 31001) {
                    ShareAuth.markExpired(platform)
                    return ShareResult.Err("${name}登录已过期，去登录页重登再试", needLogin = true)
                }
            }
        }
        if (cookie.isNotBlank()) {
            // v1.3.8：首轮转存已建任务但轮询超时时，不重建任务、不清空 TV 目录，
            // 直接对同一 task_id 再轮询一轮；小文件（首轮没走转存）才新起转存。
            val saved2 = if (saved.taskId.isNotBlank()) {
                repollSave(client, cfg, cookie, saved.taskId)
            } else {
                saveFlowWithTranscode(client, cfg, session, file, cookie)
            }
            if (saved2.url.isNotBlank()) {
                return ShareResult.Ok(PlayLink(saved2.url, playHeaders(cfg, saved2.cookie, saved2.url, cookie)))
            }
        }
        val (preview, previewPugs) = videoPreview(client, cfg, session, file)
        if (preview.isNotBlank()) {
            val note = if (cookie.isBlank()) "没登录，只能看前 2 分钟，登录后看全集" else "直链没拿到，先看 2 分钟预览"
            return ShareResult.Ok(PlayLink(preview, playHeaders(cfg, previewPugs, preview), isPreview = true, note = note))
        }
        return if (cookie.isBlank()) {
            ShareResult.Err("这个${name}分享要先登录才能播：大文件必须登录", needLogin = true)
        } else {
            ShareResult.Err("${name}解析失败：转存和直取都没拿到地址，可能是登录过期或接口变了，去登录页重登再试", needLogin = true)
        }
    }

    // ---------------- 转存原画 + 转码（登录态，照搬 PanSaveApi） ----------------

    /** internal：saveFlow 单测要读 url/savedFid。 */
    internal data class SaveOutcome(
        val url: String,
        val cookie: String,
        val savedFid: String,
        val error: String,
        val taskId: String = "",
    )

    private const val SAVE_DIR_NAME = "TV"
    // v1.3.8：3 次×1 秒根本不够大文件服务端复制（真机 4 次超时）。首轮 30 次×2 秒。
    private const val POLL_MAX = 30
    private const val POLL_INTERVAL_MS = 2000L
    // 同一 task_id 的第二轮轮询预算（再 30 秒）。
    private const val REPOLL_MAX = 15
    private val RESOLUTION_RANK = listOf("4k", "2k", "super", "high", "normal", "low")

    /** internal：单测覆盖转存整链（建目录→复用→保存→轮询→直链解析）。 */
    internal fun saveFlow(
        client: OkHttpClient,
        cfg: Config,
        session: ShareSession.QuarkUc,
        file: ShareFile,
        cookie: String,
        sleep: (Long) -> Unit = Thread::sleep,
    ): SaveOutcome {
        if (cookie.isBlank()) return SaveOutcome("", "", "", "need_login")
        val (dirId, existingFid) = ensureSaveDir(client, cfg, cookie, file)
            ?: return SaveOutcome("", "", "", "save_dir_failed")
        val savedFid = if (existingFid != null) {
            // v1.3.8：TV 目录里已有同名同大小的文件（上一轮转存超时后服务端
            // 其实已完成），直接复用，不重建任务、不清空重转。
            existingFid
        } else {
            val taskId = save(client, cfg, cookie, session, file, dirId)
                ?: return SaveOutcome("", "", "", "save_failed")
            if (taskId == "need_login") return SaveOutcome("", "", "", "need_login")
            pollTask(client, cfg, cookie, taskId, sleep)
                ?: return SaveOutcome("", "", "", "save_timeout", taskId)
        }
        val down = downloadSaved(client, cfg, cookie, savedFid)
        if (down.first.isBlank()) return SaveOutcome("", down.second, savedFid, "download_failed")
        return SaveOutcome(down.first, down.second, savedFid, "")
    }

    /** 首轮转存建了任务但轮询超时：不对同一文件重建任务，直接对同一 task_id 再轮询一轮。 */
    private fun repollSave(
        client: OkHttpClient,
        cfg: Config,
        cookie: String,
        taskId: String,
    ): SaveOutcome {
        val savedFid = pollTask(client, cfg, cookie, taskId, Thread::sleep, REPOLL_MAX)
            ?: return SaveOutcome("", "", "", "save_timeout", taskId)
        val down = downloadSaved(client, cfg, cookie, savedFid)
        if (down.first.isBlank()) {
            val transcoded = transcode(client, cfg, cookie, savedFid)
            if (transcoded.url.isNotBlank()) return keepPugs(transcoded, down.second)
            return SaveOutcome("", down.second, savedFid, "download_failed", taskId)
        }
        return SaveOutcome(down.first, down.second, savedFid, "", taskId)
    }

    private fun transcode(
        client: OkHttpClient,
        cfg: Config,
        cookie: String,
        savedFid: String,
    ): SaveOutcome {
        if (cookie.isBlank() || savedFid.isBlank()) return SaveOutcome("", "", savedFid, "need_login")
        val body = JSONObject()
            .put("fid", savedFid)
            .put("resolutions", "normal,low,high,super,2k,4k")
            .put("supports", "fmp4")
        val resp = ShareHttp.postJson(client, "${cfg.apiBase}/file/v2/play?${cfg.pr}", cookie, cfg.ua, cfg.referer, body)
        val json = resp.json ?: return SaveOutcome("", "", savedFid, "network")
        if (json.optInt("code", -1) == 31001) return SaveOutcome("", "", savedFid, "need_login")
        val list = json.optJSONObject("data")?.optJSONArray("video_list") ?: JSONArray()
        var bestUrl = ""
        var bestRank = Int.MAX_VALUE
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val rank = RESOLUTION_RANK.indexOf(item.optString("resolution")).takeIf { it >= 0 } ?: continue
            if (rank < bestRank) {
                val url = item.optJSONObject("video_info")?.optString("url").orEmpty()
                if (url.isNotBlank()) {
                    bestRank = rank
                    bestUrl = url
                }
            }
        }
        if (bestUrl.isBlank()) {
            LogBus.w(TAG, "转码无可用档位")
            return SaveOutcome("", "", savedFid, "transcode_failed")
        }
        // v1.3.8：file/v2/play 响应下发的 __pugs 别丢，转码 m3u8 回源也要带它。
        return SaveOutcome(bestUrl, resp.pugs, savedFid, "")
    }

    /** 转存成功但直取失败时再试转码。 */
    private fun saveFlowWithTranscode(
        client: OkHttpClient,
        cfg: Config,
        session: ShareSession.QuarkUc,
        file: ShareFile,
        cookie: String,
    ): SaveOutcome {
        val saved = saveFlow(client, cfg, session, file, cookie)
        if (saved.url.isNotBlank() || saved.savedFid.isBlank()) return saved
        // v1.3.14：转码只认自己响应的 pugs 会把直取那一步新鲜的丢了，补回来。
        return keepPugs(transcode(client, cfg, cookie, saved.savedFid), saved.cookie)
    }

    /**
     * 找 TV 转存目录。返回（dirId，TV 目录里已有的同名同大小文件的 fid 或 null）：
     * 同名同大小视为同一文件，调用方直接复用它，不重建转存任务。
     */
    private fun ensureSaveDir(
        client: OkHttpClient,
        cfg: Config,
        cookie: String,
        file: ShareFile,
    ): Pair<String, String?>? {
        val sort = ShareHttp.getJson(
            client,
            "${cfg.apiBase}/file/sort?${cfg.pr}&pdir_fid=0&_page=1&_size=200&_sort=file_type:asc,updated_at:desc",
            cookie, cfg.ua, cfg.referer,
        ).json ?: return null
        val list = sort.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("file_name") == SAVE_DIR_NAME) {
                val dirId = item.optString("fid")
                if (dirId.isNotBlank()) {
                    val existing = findSameFile(client, cfg, cookie, dirId, file)
                    if (existing != null) return dirId to existing
                    clearSaveDir(client, cfg, cookie, dirId)
                    return dirId to null
                }
            }
        }
        val body = JSONObject()
            .put("pdir_fid", "0")
            .put("file_name", SAVE_DIR_NAME)
            .put("dir_path", "")
            .put("dir_init_lock", "false")
        val created = ShareHttp.postJson(client, "${cfg.apiBase}/file?${cfg.pr}", cookie, cfg.ua, cfg.referer, body).json
            ?: return null
        val dirId = created.optJSONObject("data")?.optString("fid")?.takeIf { it.isNotBlank() } ?: return null
        return dirId to null
    }

    /** TV 目录里找与目标同名同大小的文件（上一轮转存超时后服务端其实已完成时复用）。 */
    private fun findSameFile(
        client: OkHttpClient,
        cfg: Config,
        cookie: String,
        dirId: String,
        file: ShareFile,
    ): String? {
        val sort = ShareHttp.getJson(
            client,
            "${cfg.apiBase}/file/sort?${cfg.pr}&pdir_fid=${ShareHttp.enc(dirId)}&_page=1&_size=200&_sort=file_type:asc,updated_at:desc",
            cookie, cfg.ua, cfg.referer,
        ).json ?: return null
        val list = sort.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("file_name") == file.name && item.optLong("size", -1L) == file.size) {
                return item.optString("fid").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun clearSaveDir(client: OkHttpClient, cfg: Config, cookie: String, dirId: String) {
        val sort = ShareHttp.getJson(
            client,
            "${cfg.apiBase}/file/sort?${cfg.pr}&pdir_fid=${ShareHttp.enc(dirId)}&_page=1&_size=200&_sort=file_type:asc,updated_at:desc",
            cookie, cfg.ua, cfg.referer,
        ).json ?: return
        val list = sort.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        if (list.length() == 0) return
        val fids = JSONArray()
        for (i in 0 until list.length()) {
            list.optJSONObject(i)?.optString("fid")?.takeIf { it.isNotBlank() }?.let { fids.put(it) }
        }
        if (fids.length() == 0) return
        val body = JSONObject()
            .put("action_type", "2")
            .put("filelist", fids.toString())
            .put("exclude_fids", "")
        ShareHttp.postJson(client, "${cfg.apiBase}/file/delete?${cfg.pr}", cookie, cfg.ua, cfg.referer, body)
    }

    private fun save(
        client: OkHttpClient,
        cfg: Config,
        cookie: String,
        session: ShareSession.QuarkUc,
        file: ShareFile,
        dirId: String,
    ): String? {
        val body = JSONObject()
            .put("fid_list", JSONArray().put(file.fid))
            .put("fid_token_list", JSONArray().put(file.fidToken))
            .put("to_pdir_fid", dirId)
            .put("pwd_id", session.pwdId)
            .put("stoken", session.stoken)
            .put("pdir_fid", "0")
            .put("scene", "link")
        val json = ShareHttp.postJson(client, "${cfg.apiBase}/share/sharepage/save?${cfg.pr}", cookie, cfg.ua, cfg.referer, body).json
            ?: return null
        if (json.optInt("code", -1) == 31001) return "need_login"
        return json.optJSONObject("data")?.optString("task_id")?.takeIf { it.isNotBlank() }
    }

    /** internal：单测覆盖轮询次数。maxPolls 默认 POLL_MAX，复轮询传更小的预算。 */
    internal fun pollTask(
        client: OkHttpClient,
        cfg: Config,
        cookie: String,
        taskId: String,
        sleep: (Long) -> Unit,
        maxPolls: Int = POLL_MAX,
    ): String? {
        for (retry in 0 until maxPolls) {
            val json = ShareHttp.getJson(
                client,
                "${cfg.apiBase}/task?${cfg.pr}&task_id=${ShareHttp.enc(taskId)}&retry_index=$retry",
                cookie, cfg.ua, cfg.referer,
            ).json
            val fids = json?.optJSONObject("data")?.optJSONObject("save_as")
                ?.optJSONArray("save_as_top_fids")
            if (fids != null && fids.length() > 0) {
                return fids.optString(0).takeIf { it.isNotBlank() }
            }
            if (retry < maxPolls - 1) sleep(POLL_INTERVAL_MS)
        }
        LogBus.w(TAG, "转存轮询超时")
        return null
    }

    private fun downloadSaved(
        client: OkHttpClient,
        cfg: Config,
        cookie: String,
        savedFid: String,
    ): Pair<String, String> {
        val body = JSONObject()
        if (cfg.fidsAsList) body.put("fids", JSONArray().put(savedFid)) else body.put("fids", savedFid)
        val resp = ShareHttp.postJson(client, "${cfg.apiBase}/file/download?${cfg.pr}&uc_param_str=", cookie, cfg.ua, cfg.referer, body)
        val json = resp.json ?: return "" to ""
        val data = json.opt("data") ?: return "" to ""
        // v1.3.8：data 有两种形态——对象数组取 download_url（与分享端 download 同款），
        // 字符串数组取字符串本身；只按一种取会把另一种判成失败。
        val url = when (data) {
            is JSONArray -> if (data.length() > 0) {
                data.optJSONObject(0)?.optString("download_url").orEmpty()
                    .ifBlank { (data.opt(0) as? String).orEmpty() }
            } else ""
            is JSONObject -> data.optString("download_url")
            is String -> data
            else -> ""
        }
        return url to resp.pugs
    }
}
