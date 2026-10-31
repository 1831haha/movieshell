package com.movieshell.data.repo

import com.movieshell.data.source.CmsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * v0.127.0：各平台自有公开音乐接口（GD Studio 只支持 netease，搜索/榜单不能靠它）。
 * 2026-10-20 逐家 curl 实测结论：
 * - tx(QQ)：c.y.qq.com 搜索/榜单/榜单曲目全通，无需登录态；urlId=songmid，
 *   封面按 albummid 拼 y.gtimg.cn 直链。
 * - kw(酷我)：search.kuwo.cn 老接口通（返回单引号伪 JSON，下方宽容转换后解析）；
 *   www.kuwo.cn 的 api/www 接口实测被判 illegal（csrf 也救不回来），不用。
 *   榜单走 kbangserver（id=16 热歌榜/93 飙升榜实测有数据）；urlId=MUSICRID 数字段。
 * - kg(酷狗)：songsearch 搜索通（urlId=FileHash，正是 LX kg 后端要的 hash）；
 *   榜单走 mobilecdn 的 v5/rank/list + v3/rank/song（必须 http，https 空响应）。
 * - mg(咪咕)：pd.musicapp 的 search_all.do 通（iPhone UA + Referer，
 *   urlId=copyrightId，与 LX mg 后端的 copyrightId 参数对齐）；
 *   榜单端点逐个实测均不可用（路由不支持/404），charts 返回空表不假装。
 *
 * Track 口径与 MusicApi 一致：source 写 LX code，urlId 放该平台主键，
 * picId 能直出图片地址的直接放完整 URL（MusicApi.picImageUrl 对 http 开头直通）。
 * 网络走 CmsApi.defaultClient() 共享单例，不新造 client。
 */
object PlatformMusicApi {
    private const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; 24117RK2CC) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val UA_PLAIN = "Mozilla/5.0"
    private const val UA_IPHONE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 " +
            "(KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1"

    private val http = CmsApi.defaultClient()

    private suspend fun get(
        url: String,
        ua: String,
        referer: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("User-Agent", ua)
        if (referer != null) b.header("Referer", referer)
        http.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("音乐接口请求失败：${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** MusicApi.search 的非 wy 分发入口（platform 已归一，为 tx/kw/kg/mg 之一）。 */
    suspend fun search(
        platform: String,
        query: String,
        page: Int,
        count: Int,
    ): List<MusicApi.Track> = when (platform) {
        "tx" -> searchTx(query, page, count)
        "kw" -> searchKw(query, page, count)
        "kg" -> searchKg(query, page, count)
        "mg" -> searchMg(query, page, count)
        else -> emptyList()
    }

    /** 非 wy 平台榜单（mg 实测无可用榜单端点，返回空）。 */
    suspend fun charts(platform: String): List<MusicApi.ChartEntry> = when (platform) {
        "tx" -> chartsTx()
        "kw" -> listOf(
            MusicApi.ChartEntry("16", "酷我热歌榜"),
            MusicApi.ChartEntry("93", "酷我飙升榜"),
        )
        "kg" -> chartsKg()
        else -> emptyList()
    }

    /** 非 wy 平台榜单曲目。 */
    suspend fun chartTracks(
        platform: String,
        chartId: String,
    ): List<MusicApi.Track> = when (platform) {
        "tx" -> chartTracksTx(chartId)
        "kw" -> chartTracksKw(chartId)
        "kg" -> chartTracksKg(chartId)
        else -> emptyList()
    }

    // ---------------- tx（QQ 音乐） ----------------

    private fun txTrack(o: JSONObject): MusicApi.Track? {
        val mid = o.optString("songmid")
        if (mid.isBlank()) return null
        val singers = o.optJSONArray("singer")?.let { a ->
            (0 until a.length()).mapNotNull {
                a.optJSONObject(it)?.optString("name")?.ifBlank { null }
            }
        }.orEmpty()
        val albummid = o.optString("albummid")
        return MusicApi.Track(
            id = "tx:$mid",
            name = o.optString("songname").ifBlank { "未知曲目" },
            artist = singers.joinToString("、").ifBlank { "未知歌手" },
            album = o.optString("albumname"),
            picId = if (albummid.isBlank()) {
                ""
            } else {
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albummid.jpg"
            },
            urlId = mid,
            source = "tx",
        )
    }

    private suspend fun searchTx(
        query: String,
        page: Int,
        count: Int,
    ): List<MusicApi.Track> {
        val body = get(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
                "?w=${enc(query)}&p=$page&n=$count&format=json",
            UA_MOBILE,
        )
        val list = JSONObject(body).optJSONObject("data")
            ?.optJSONObject("song")?.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(::txTrack) }
    }

    private suspend fun chartsTx(): List<MusicApi.ChartEntry> {
        val body = get(
            "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?format=json&g_tk=5381" +
                "&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0" +
                "&platform=yqq.json&needNewCode=0",
            UA_MOBILE,
        )
        val arr = JSONObject(body).optJSONObject("data")
            ?.optJSONArray("topList") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("topTitle")
            val id = o.optInt("id", 0)
            if (id == 0 || title.isBlank()) null
            else MusicApi.ChartEntry(id.toString(), title, o.optString("picUrl"))
        }.take(10)
    }

    private suspend fun chartTracksTx(chartId: String): List<MusicApi.Track> {
        val body = get(
            "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=$chartId" +
                "&format=json&inCharset=utf8&outCharset=utf-8",
            UA_MOBILE,
        )
        val arr = JSONObject(body).optJSONArray("songlist") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optJSONObject("data")?.let(::txTrack)
        }.take(100)
    }

    /**
     * v0.294.16：QQ 音乐公开歌词（songmid → LRC 原文）。
     * c.y.qq.com/lyric/…/fcg_query_lyric_new.fcg，Referer=y.qq.com，nobase64=1；
     * 失败或无词返回空串。HTML 实体做轻量解码（部分曲目 lyric 字段带 &#xx;）。
     */
    suspend fun lyricRawTx(songmid: String): String {
        if (songmid.isBlank()) return ""
        val body = runCatching {
            get(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=${enc(songmid)}&g_tk=5381&format=json" +
                    "&inCharset=utf8&outCharset=utf-8&nobase64=1",
                UA_MOBILE,
                referer = "https://y.qq.com/",
            )
        }.getOrNull() ?: return ""
        // 偶发 JSONP 包裹 MusicJsonCallback({...})，剥一层再解析
        val jsonText = body.trim().let { s ->
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start >= 0 && end > start) s.substring(start, end + 1) else s
        }
        val raw = runCatching { JSONObject(jsonText).optString("lyric") }.getOrDefault("")
        if (raw.isBlank()) return ""
        return decodeLyricEntities(raw)
    }

    /** QQ 歌词字段里常见数字实体（&#58; 等）与命名实体。 */
    private fun decodeLyricEntities(s: String): String {
        var out = s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        // &#NNN; / &#xHH; 数字实体
        out = Regex("""&#(\d+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        return out
    }

    // ---------------- kw（酷我） ----------------

    /**
     * 酷我 search.kuwo.cn 返回单引号伪 JSON（值均为字符串）。宽容转换：
     * 逐字扫描，单引号字符串转 JSON 字符串（处理 \' 转义并转义 " 与反斜杠），
     * 字符串外字符原样保留。只服务这一个已知格式，不做通用 JS 解析。
     */
    private fun looseToJson(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\'') {
                sb.append('"')
                i++
                while (i < text.length) {
                    val ch = text[i]
                    when {
                        ch == '\\' && i + 1 < text.length -> {
                            when (text[i + 1]) {
                                '\'' -> sb.append('\'')
                                '\\' -> sb.append("\\\\")
                                else -> {
                                    sb.append("\\\\")
                                    sb.append(text[i + 1])
                                }
                            }
                            i += 2
                        }
                        ch == '\'' -> {
                            sb.append('"')
                            i++
                            break
                        }
                        ch == '"' -> {
                            sb.append("\\\"")
                            i++
                        }
                        else -> {
                            sb.append(ch)
                            i++
                        }
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 酷我字段里常见 HTML 实体解码（歌名/歌手名用）。 */
    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    private suspend fun searchKw(
        query: String,
        page: Int,
        count: Int,
    ): List<MusicApi.Track> {
        val body = get(
            "http://search.kuwo.cn/r.s?all=${enc(query)}&ft=music&itemset=web_2013" +
                "&client=kt&pn=${page - 1}&rn=$count&rformat=json&encoding=utf8",
            UA_MOBILE,
        )
        val arr = JSONObject(looseToJson(body)).optJSONArray("abslist") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val rid = o.optString("MUSICRID").removePrefix("MUSIC_")
            if (rid.isBlank()) null else MusicApi.Track(
                id = "kw:$rid",
                name = decodeEntities(o.optString("NAME")).ifBlank { "未知曲目" },
                artist = decodeEntities(o.optString("ARTIST")).ifBlank { "未知歌手" },
                album = decodeEntities(o.optString("ALBUM")),
                // 搜索结果无专辑封面字段（榜单有），留空走播放页海报匹配补图
                picId = "",
                urlId = rid,
                source = "kw",
            )
        }
    }

    private suspend fun chartTracksKw(chartId: String): List<MusicApi.Track> {
        val body = get(
            "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&type=bang&data=content&id=$chartId",
            UA_PLAIN,
        )
        val arr = JSONObject(body).optJSONArray("musiclist") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null else MusicApi.Track(
                id = "kw:$id",
                name = decodeEntities(o.optString("name")).ifBlank { "未知曲目" },
                artist = decodeEntities(o.optString("artist")).ifBlank { "未知歌手" },
                album = decodeEntities(o.optString("album")),
                picId = o.optString("pic"),
                urlId = id,
                source = "kw",
            )
        }
    }

    // ---------------- kg（酷狗） ----------------

    /** 酷狗图片 URL 里的 {size} 占位换成 400。 */
    private fun kgPic(url: String): String = url.replace("{size}", "400")

    private suspend fun searchKg(
        query: String,
        page: Int,
        count: Int,
    ): List<MusicApi.Track> {
        val body = get(
            "https://songsearch.kugou.com/song_search_v2" +
                "?keyword=${enc(query)}&page=$page&pagesize=$count",
            UA_MOBILE,
        )
        val arr = JSONObject(body).optJSONObject("data")
            ?.optJSONArray("lists") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val hash = o.optString("FileHash")
            if (hash.isBlank()) null else MusicApi.Track(
                id = "kg:$hash",
                name = o.optString("SongName").ifBlank { "未知曲目" },
                artist = o.optString("SingerName").ifBlank { "未知歌手" },
                album = o.optString("AlbumName"),
                picId = kgPic(o.optString("Image")),
                urlId = hash,
                source = "kg",
            )
        }
    }

    private suspend fun chartsKg(): List<MusicApi.ChartEntry> {
        val body = get(
            "http://mobilecdn.kugou.com/api/v5/rank/list?version=9108&plat=0&page=1&pagesize=30",
            UA_PLAIN,
        )
        val arr = JSONObject(body).optJSONObject("data")
            ?.optJSONArray("info") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("rankid", 0)
            val name = o.optString("rankname")
            if (id == 0 || name.isBlank()) null
            else MusicApi.ChartEntry(id.toString(), name, kgPic(o.optString("imgurl")))
        }.take(10)
    }

    private suspend fun chartTracksKg(chartId: String): List<MusicApi.Track> {
        val body = get(
            "http://mobilecdn.kugou.com/api/v3/rank/song?version=9108&plat=0" +
                "&rankid=$chartId&page=1&pagesize=100&parentid=0",
            UA_PLAIN,
        )
        val arr = JSONObject(body).optJSONObject("data")
            ?.optJSONArray("info") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val hash = o.optString("hash")
            val singers = o.optJSONArray("authors")?.let { a ->
                (0 until a.length()).mapNotNull {
                    a.optJSONObject(it)?.optString("author_name")?.ifBlank { null }
                }
            }.orEmpty()
            if (hash.isBlank()) null else MusicApi.Track(
                id = "kg:$hash",
                name = o.optString("songname").ifBlank { "未知曲目" },
                artist = singers.joinToString("、").ifBlank { "未知歌手" },
                album = "",
                picId = kgPic(o.optString("album_sizable_cover")),
                urlId = hash,
                source = "kg",
            )
        }
    }

    // ---------------- mg（咪咕） ----------------

    private suspend fun searchMg(
        query: String,
        page: Int,
        count: Int,
    ): List<MusicApi.Track> {
        val switch = enc("{\"song\":1,\"album\":0,\"singer\":0,\"tagSong\":0," +
            "\"mvSong\":0,\"songlist\":0,\"bestShow\":1}")
        val body = get(
            "http://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do" +
                "?text=${enc(query)}&pageNo=$page&pageSize=$count" +
                "&ua=Android_migu&searchSwitch=$switch",
            UA_IPHONE,
            referer = "http://music.migu.cn/",
        )
        val arr = JSONObject(body).optJSONObject("songResultData")
            ?.optJSONArray("result") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // LX mg 后端按 copyrightId 取链，urlId 必须放 copyrightId（不是内容 id）
            val cid = o.optString("copyrightId")
            if (cid.isBlank()) return@mapNotNull null
            val singers = o.optJSONArray("singers")?.let { a ->
                (0 until a.length()).mapNotNull {
                    a.optJSONObject(it)?.optString("name")?.ifBlank { null }
                }
            }.orEmpty()
            val album = o.optJSONArray("albums")?.optJSONObject(0)
                ?.optString("name").orEmpty()
            val img = o.optJSONArray("imgItems")?.optJSONObject(0)
                ?.optString("img").orEmpty()
            MusicApi.Track(
                id = "mg:$cid",
                name = o.optString("name").ifBlank { "未知曲目" },
                artist = singers.joinToString("、").ifBlank { "未知歌手" },
                album = album,
                picId = img,
                urlId = cid,
                source = "mg",
            )
        }
    }
}
