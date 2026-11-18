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

    /** 游客 2 分钟预览 m3u8（sharepage/video_preview，2026-11-17 沙箱实测）。 */
    private fun videoPreview(
        client: OkHttpClient,
        cfg: Config,
        session: ShareSession.QuarkUc,
        file: ShareFile,
    ): String {
        // v1.3.6：fid_token 与 stoken 常含 +/=，查询串里的值一律编码。
        val url = "${cfg.apiBase}/share/sharepage/video_preview?fid=${ShareHttp.enc(file.fid)}" +
            "&fid_token=${ShareHttp.enc(file.fidToken)}&pwd_id=${ShareHttp.enc(session.pwdId)}" +
            "&stoken=${ShareHttp.enc(session.stoken)}&is_share_preview=true"
        val resp = ShareHttp.getJson(
            client, url, "", cfg.ua, cfg.referer,
            extraHeaders = mapOf("x-clouddrive-st" to session.stoken),
        )
        val json = resp.json ?: return ""
        if (json.optInt("code", -1) != 0) return ""
        val data = json.optJSONObject("data") ?: return ""
        val playInfo = data.optJSONObject("play_info")
        val m3u8 = playInfo?.optString("url").orEmpty()
        if (m3u8.isNotBlank()) return m3u8
        return data.optString("preview_url").orEmpty()
    }

    private fun playHeaders(cfg: Config, pugs: String): Map<String, String> {
        val headers = mutableMapOf("Referer" to cfg.referer, "User-Agent" to cfg.ua)
        if (pugs.isNotBlank()) headers["Cookie"] = "__pugs=$pugs"
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
        if (cookie.isNotBlank() && file.size > LARGE_SIZE) {
            val saved = saveFlowWithTranscode(client, cfg, session, file, cookie)
            if (saved.url.isNotBlank()) {
                return ShareResult.Ok(PlayLink(saved.url, playHeaders(cfg, saved.cookie)))
            }
        }
        val (url, pugs, code) = download(client, cfg, session, file, "")
        if (url.isNotBlank()) {
            return ShareResult.Ok(PlayLink(url, playHeaders(cfg, pugs)))
        }
        if (code == 31001) {
            ShareAuth.markExpired(platform)
            return ShareResult.Err("${name}登录已过期，去登录页重登再试", needLogin = true)
        }
        if (code == 23018 || code == 31001) {
            if (cookie.isNotBlank()) {
                val (url2, pugs2, code2) = download(client, cfg, session, file, cookie)
                if (url2.isNotBlank()) {
                    return ShareResult.Ok(PlayLink(url2, playHeaders(cfg, pugs2)))
                }
                if (code2 == 31001) {
                    ShareAuth.markExpired(platform)
                    return ShareResult.Err("${name}登录已过期，去登录页重登再试", needLogin = true)
                }
            }
        }
        if (cookie.isNotBlank()) {
            val saved = saveFlowWithTranscode(client, cfg, session, file, cookie)
            if (saved.url.isNotBlank()) {
                return ShareResult.Ok(PlayLink(saved.url, playHeaders(cfg, saved.cookie)))
            }
        }
        val preview = videoPreview(client, cfg, session, file)
        if (preview.isNotBlank()) {
            val note = if (cookie.isBlank()) "没登录，只能看前 2 分钟，登录后看全集" else "直链没拿到，先看 2 分钟预览"
            return ShareResult.Ok(PlayLink(preview, playHeaders(cfg, ""), isPreview = true, note = note))
        }
        return if (cookie.isBlank()) {
            ShareResult.Err("这个${name}分享要先登录才能播：大文件必须登录", needLogin = true)
        } else {
            ShareResult.Err("${name}解析失败：转存和直取都没拿到地址，可能是登录过期或接口变了，去登录页重登再试", needLogin = true)
        }
    }

    // ---------------- 转存原画 + 转码（登录态，照搬 PanSaveApi） ----------------

    private data class SaveOutcome(val url: String, val cookie: String, val savedFid: String, val error: String)

    private const val SAVE_DIR_NAME = "TV"
    private const val POLL_MAX = 3
    private const val POLL_INTERVAL_MS = 1000L
    private val RESOLUTION_RANK = listOf("4k", "2k", "super", "high", "normal", "low")

    private fun saveFlow(
        client: OkHttpClient,
        cfg: Config,
        session: ShareSession.QuarkUc,
        file: ShareFile,
        cookie: String,
        sleep: (Long) -> Unit = Thread::sleep,
    ): SaveOutcome {
        if (cookie.isBlank()) return SaveOutcome("", "", "", "need_login")
        val dirId = ensureSaveDir(client, cfg, cookie)
            ?: return SaveOutcome("", "", "", "save_dir_failed")
        val taskId = save(client, cfg, cookie, session, file, dirId)
            ?: return SaveOutcome("", "", "", "save_failed")
        if (taskId == "need_login") return SaveOutcome("", "", "", "need_login")
        val savedFid = pollTask(client, cfg, cookie, taskId, sleep)
            ?: return SaveOutcome("", "", "", "save_timeout")
        val down = downloadSaved(client, cfg, cookie, savedFid)
        if (down.first.isBlank()) return SaveOutcome("", down.second, savedFid, "download_failed")
        return SaveOutcome(down.first, down.second, savedFid, "")
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
        return SaveOutcome(bestUrl, "", savedFid, "")
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
        return transcode(client, cfg, cookie, saved.savedFid)
    }

    private fun ensureSaveDir(client: OkHttpClient, cfg: Config, cookie: String): String? {
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
                    clearSaveDir(client, cfg, cookie, dirId)
                    return dirId
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
        return created.optJSONObject("data")?.optString("fid")?.takeIf { it.isNotBlank() }
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

    private fun pollTask(
        client: OkHttpClient,
        cfg: Config,
        cookie: String,
        taskId: String,
        sleep: (Long) -> Unit,
    ): String? {
        for (retry in 0 until POLL_MAX) {
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
            if (retry < POLL_MAX - 1) sleep(POLL_INTERVAL_MS)
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
        val url = when (data) {
            is JSONArray -> if (data.length() > 0) data.optString(0) else ""
            is JSONObject -> data.optString("download_url")
            else -> ""
        }
        return url to resp.pugs
    }
}
