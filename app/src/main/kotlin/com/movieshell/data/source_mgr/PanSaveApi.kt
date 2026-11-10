package com.movieshell.data.source_mgr

import com.movieshell.core.log.LogBus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.1.23 扩充：夸克/UC 转存原画与转码（CatVod QuarkApi/UCApi 同款链路）。
 *
 * 两家接口同名同形，只差 host 与 pr：
 * - 夸克：https://drive-pc.quark.cn/1/clouddrive/，pr=ucpro&fr=pc；
 * - UC：https://pc-api.uc.cn/1/clouddrive/，pr=UCBrowser&fr=pc。
 * 另有一处差别：取原画直链时夸克的 fids 传单个字符串，UC 传单元素数组。
 *
 * 转存链（登录态，游客三步全回 401 code 31001）：
 * 1. 找/建转存目录：根目录找名叫 TV 的文件夹，没有就建；找到先清空；
 * 2. 转存：POST share/sharepage/save → data.task_id；
 * 3. 轮询：GET task?task_id=..&retry_index=0..2，最多 3 次、间隔 1 秒，
 *    data.save_as.save_as_top_fids[0] 即转存后的新 fid；
 * 4. 原画直链：POST file/download {"fids": 新 fid} → data[0]。
 * 转码在转存之后调：POST file/v2/play {"fid": 新 fid, ...} →
 * data.video_list[] 里按 4k>2k>super>high>normal>low 取 video_info.url。
 *
 * 直链回源要带的 __pugs Cookie 从 file/download 响应的 Set-Cookie 里取，
 * 随 url 一起返回；裸 URL 回源会 403（UC 实测、夸克同机制）。
 * 全部登录后步骤都未经真机验证（沙箱无夸克/UC 登录 Cookie），
 * 单测用 MockWebServer 覆盖，见 PanSaveApiTest。
 */
object PanSaveApi {

    private const val TAG = "PanSave"
    private const val SAVE_DIR_NAME = "TV"
    private const val POLL_MAX = 3
    private const val POLL_INTERVAL_MS = 1000L
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 画质档位从高到低，转码取第一个有的。 */
    private val RESOLUTION_RANK = listOf("4k", "2k", "super", "high", "normal", "low")

    /** saveAndDownload / transcode 的返回：直链、回源 Cookie、转存后 fid、错误码（空=成功）。 */
    data class SaveOutcome(val url: String, val cookie: String, val savedFid: String, val error: String)

    // ---------------- Java 桥入口（PY 规则调） ----------------

    /** 转存原画并取直链。error 为空表示成功；savedFid 非空但 url 为空时可再调 transcode。 */
    @JvmStatic
    fun saveAndDownload(
        cookie: String, apiBase: String, pr: String, ua: String, referer: String,
        pwdId: String, stoken: String, fid: String, fidToken: String, fidsAsList: Boolean,
    ): JSONObject {
        val o = saveAndDownloadCore(cookie, apiBase, pr, ua, referer, pwdId, stoken, fid, fidToken, fidsAsList)
        return JSONObject().put("url", o.url).put("cookie", o.cookie)
            .put("saved_fid", o.savedFid).put("error", o.error)
    }

    /** 转存后的文件取转码 m3u8。error 为空表示成功。 */
    @JvmStatic
    fun transcode(
        cookie: String, apiBase: String, pr: String, ua: String, referer: String, savedFid: String,
    ): JSONObject {
        val o = transcodeCore(cookie, apiBase, pr, ua, referer, savedFid)
        return JSONObject().put("url", o.url).put("cookie", o.cookie)
            .put("saved_fid", o.savedFid).put("error", o.error)
    }

    // ---------------- 可测核心 ----------------

    fun saveAndDownloadCore(
        cookie: String, apiBase: String, pr: String, ua: String, referer: String,
        pwdId: String, stoken: String, fid: String, fidToken: String, fidsAsList: Boolean,
        client: OkHttpClient = defaultClient(), sleep: (Long) -> Unit = Thread::sleep,
    ): SaveOutcome {
        if (cookie.isBlank()) return SaveOutcome("", "", "", "need_login")
        val dirId = ensureSaveDir(client, apiBase, pr, cookie, ua, referer)
            ?: return SaveOutcome("", "", "", "save_dir_failed")
        val taskId = save(client, apiBase, pr, cookie, ua, referer, pwdId, stoken, fid, fidToken, dirId)
            ?: return SaveOutcome("", "", "", "save_failed")
        if (taskId == "need_login") return SaveOutcome("", "", "", "need_login")
        val savedFid = pollTask(client, apiBase, pr, cookie, ua, referer, taskId, sleep)
            ?: return SaveOutcome("", "", "", "save_timeout")
        val down = download(client, apiBase, pr, cookie, ua, referer, savedFid, fidsAsList)
        if (down.first.isBlank()) return SaveOutcome("", down.second, savedFid, "download_failed")
        return SaveOutcome(down.first, down.second, savedFid, "")
    }

    fun transcodeCore(
        cookie: String, apiBase: String, pr: String, ua: String, referer: String, savedFid: String,
        client: OkHttpClient = defaultClient(),
    ): SaveOutcome {
        if (cookie.isBlank() || savedFid.isBlank()) return SaveOutcome("", "", savedFid, "need_login")
        val body = JSONObject()
            .put("fid", savedFid)
            .put("resolutions", "normal,low,high,super,2k,4k")
            .put("supports", "fmp4")
        val resp = postJson(client, "${apiBase}file/v2/play?$pr", cookie, ua, referer, body)
        val json = resp.json ?: return SaveOutcome("", "", savedFid, "network")
        if (json.optInt("code", -1) == 31001) return SaveOutcome("", "", savedFid, "need_login")
        val list = json.optJSONObject("data")?.optJSONArray("video_list") ?: JSONArray()
        var bestUrl = ""
        var bestRank = Int.MAX_VALUE
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val res = item.optString("resolution")
            val rank = RESOLUTION_RANK.indexOf(res).takeIf { it >= 0 } ?: continue
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

    // ---------------- 转存三步 ----------------

    /** 找或建 TV 转存目录；找到旧目录先清空。失败返回 null。 */
    private fun ensureSaveDir(
        client: OkHttpClient, apiBase: String, pr: String, cookie: String, ua: String, referer: String,
    ): String? {
        val sort = getJson(
            client,
            "${apiBase}file/sort?$pr&pdir_fid=0&_page=1&_size=200&_sort=file_type:asc,updated_at:desc",
            cookie, ua, referer,
        ).json ?: return null
        val list = sort.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("file_name") == SAVE_DIR_NAME) {
                val dirId = item.optString("fid")
                if (dirId.isNotBlank()) {
                    clearSaveDir(client, apiBase, pr, cookie, ua, referer, dirId)
                    return dirId
                }
            }
        }
        val body = JSONObject()
            .put("pdir_fid", "0")
            .put("file_name", SAVE_DIR_NAME)
            .put("dir_path", "")
            .put("dir_init_lock", "false")
        val created = postJson(client, "${apiBase}file?$pr", cookie, ua, referer, body).json
            ?: return null
        return created.optJSONObject("data")?.optString("fid")?.takeIf { it.isNotBlank() }
    }

    /** 清空转存目录（删里面全部文件，删失败也不拦转存）。 */
    private fun clearSaveDir(
        client: OkHttpClient, apiBase: String, pr: String, cookie: String, ua: String, referer: String, dirId: String,
    ) {
        val sort = getJson(
            client,
            "${apiBase}file/sort?$pr&pdir_fid=$dirId&_page=1&_size=200&_sort=file_type:asc,updated_at:desc",
            cookie, ua, referer,
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
        postJson(client, "${apiBase}file/delete?$pr", cookie, ua, referer, body)
    }

    /** 转存一步：返回 task_id；登录失效返回 "need_login"；其他失败返回 null。 */
    private fun save(
        client: OkHttpClient, apiBase: String, pr: String, cookie: String, ua: String, referer: String,
        pwdId: String, stoken: String, fid: String, fidToken: String, dirId: String,
    ): String? {
        val body = JSONObject()
            .put("fid_list", JSONArray().put(fid))
            .put("fid_token_list", JSONArray().put(fidToken))
            .put("to_pdir_fid", dirId)
            .put("pwd_id", pwdId)
            .put("stoken", stoken)
            .put("pdir_fid", "0")
            .put("scene", "link")
        val json = postJson(client, "${apiBase}share/sharepage/save?$pr", cookie, ua, referer, body).json
            ?: return null
        if (json.optInt("code", -1) == 31001) return "need_login"
        return json.optJSONObject("data")?.optString("task_id")?.takeIf { it.isNotBlank() }
    }

    /** 轮询任务：拿到转存后 fid 返回，否则 null（超时）。 */
    private fun pollTask(
        client: OkHttpClient, apiBase: String, pr: String, cookie: String, ua: String, referer: String,
        taskId: String, sleep: (Long) -> Unit,
    ): String? {
        for (retry in 0 until POLL_MAX) {
            val json = getJson(
                client,
                "${apiBase}task?$pr&task_id=$taskId&retry_index=$retry",
                cookie, ua, referer,
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

    /** 取转存后文件的直链；返回（直链，__pugs）。 */
    private fun download(
        client: OkHttpClient, apiBase: String, pr: String, cookie: String, ua: String, referer: String,
        savedFid: String, fidsAsList: Boolean,
    ): Pair<String, String> {
        val body = JSONObject()
        if (fidsAsList) body.put("fids", JSONArray().put(savedFid)) else body.put("fids", savedFid)
        val resp = postJson(client, "${apiBase}file/download?$pr&uc_param_str=", cookie, ua, referer, body)
        val json = resp.json ?: return "" to ""
        val data = json.opt("data") ?: return "" to ""
        val url = when (data) {
            is JSONArray -> if (data.length() > 0) data.optString(0) else ""
            is JSONObject -> data.optString("download_url")
            else -> ""
        }
        return url to resp.pugs
    }

    // ---------------- 小件 ----------------

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private data class Resp(val json: JSONObject?, val pugs: String)

    private fun postJson(
        client: OkHttpClient, url: String, cookie: String, ua: String, referer: String, body: JSONObject,
    ): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Content-Type", "application/json")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(client, req)
    }

    private fun getJson(
        client: OkHttpClient, url: String, cookie: String, ua: String, referer: String,
    ): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .get()
            .build()
        return execute(client, req)
    }

    private fun execute(client: OkHttpClient, req: Request): Resp {
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val pugs = resp.headers("Set-Cookie")
                    .firstOrNull { it.startsWith("__pugs=") }
                    ?.substringAfter("__pugs=")?.substringBefore(';').orEmpty()
                Resp(json, pugs)
            }
        }.onFailure { LogBus.w(TAG, "网盘接口请求失败：${it.message}") }.getOrDefault(Resp(null, ""))
    }
}
