package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File

/** 直播频道 */
@Serializable
data class LiveChannel(
    /** 稳定 id：sourceKey|url（跨刷新不变，收藏键用它） */
    val id: String = "",
    val name: String = "",
    val logo: String = "",
    val url: String = "",
    val group: String = "",
    val ua: String = "",
    val referer: String = "",
    val epgUrl: String = "",
    val favorite: Boolean = false,
    /** manual / m3u:<订阅名> / plugin:<文件名> */
    val sourceKey: String = "",
    /** 手动添加的频道可删除 */
    val manual: Boolean = false,
)

/** 直播订阅：M3U 订阅地址 / 本地 M3U 文件 */
@Serializable
data class LiveSource(
    val name: String = "",
    /** M3U_URL / LOCAL_FILE */
    val type: String = "M3U_URL",
    /** 地址或本地绝对路径 */
    val url: String = "",
) {
    companion object {
        const val M3U_URL = "M3U_URL"
        const val LOCAL_FILE = "LOCAL_FILE"
    }
}

private val liveJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 直播源管理（v0.24.0，单一 Store）。
 *
 * 来源：① M3U 订阅 URL（#EXTINF 解析台名/tvg-logo/group-title；#EXTM3U 的
 * x-tvg-url 做播放列表级 EPG；非 #EXTM3U 文本按 TXT“台名,地址”兜底）
 * ② 插件市场装的 live 插件：xiaying/live/ 目录下的 json 文件，顶层 lives 数组
 * （DsPlayer live 规范：{name,url,ua,epg}，url 必填）
 * ③ 手动添加的单个频道 ④ 本地 M3U 文件（SAF 导入，拷进 liveDir）。
 *
 * 频道 id = "sourceKey|url"，跨刷新稳定，收藏按 id 存。
 */
object LiveStore {

    private const val PREFS = "live_prefs"
    private const val KEY_SOURCES = "live_sources"
    private const val KEY_MANUAL = "live_manual"
    private const val KEY_FAVS = "live_favs"

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels

    private val _sources = MutableStateFlow<List<LiveSource>>(emptyList())
    val sources: StateFlow<List<LiveSource>> = _sources

    private var inited = false

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        _sources.value = readSources(context)
    }

    // ---------------- 订阅管理 ----------------

    fun addSource(context: Context, name: String, type: String, url: String): Boolean {
        val n = name.trim().ifBlank { return false }
        val u = url.trim().ifBlank { return false }
        if (_sources.value.any { it.url == u }) return false
        _sources.value = _sources.value + LiveSource(n, type, u)
        persistSources(context)
        return true
    }

    fun removeSource(context: Context, url: String) {
        _sources.value = _sources.value.filterNot { it.url == url }
        persistSources(context)
    }

    /** 本地 M3U 文件导入：拷进 liveDir 当 LOCAL_FILE 订阅 */
    fun importLocalFile(context: Context, src: File, displayName: String): Boolean {
        val dst = File(
            StoragePaths.liveDir(context),
            "import_${System.currentTimeMillis()}.m3u",
        )
        if (!runCatching { src.copyTo(dst, overwrite = true); true }.getOrDefault(false)) return false
        return addSource(context, displayName, LiveSource.LOCAL_FILE, dst.absolutePath)
    }

    // ---------------- 手动频道 ----------------

    fun addManual(
        context: Context,
        name: String,
        url: String,
        logo: String = "",
        group: String = "",
    ): Boolean {
        val n = name.trim().ifBlank { return false }
        val u = url.trim().ifBlank { return false }
        val list = readManual(context).toMutableList()
        if (list.any { it.url == u }) return false
        val key = "manual"
        list.add(
            LiveChannel(
                id = "$key|$u", name = n, logo = logo.trim(), url = u,
                group = group.trim().ifBlank { "手动添加" },
                sourceKey = key, manual = true,
                favorite = readFavs(context).contains("$key|$u"),
            ),
        )
        persistManual(context, list)
        return true
    }

    fun removeManual(context: Context, id: String) {
        persistManual(context, readManual(context).filterNot { it.id == id })
    }

    // ---------------- 收藏 ----------------

    fun toggleFavorite(context: Context, id: String) {
        val favs = readFavs(context).toMutableSet()
        if (!favs.add(id)) favs.remove(id)
        persistFavs(context, favs)
        _channels.value = _channels.value.map {
            if (it.id == id) it.copy(favorite = favs.contains(id)) else it
        }
    }

    // ---------------- 刷新聚合 ----------------

    /**
     * 重拉所有来源并聚合；IO 线程调用。
     * @return 未解析出频道的订阅名（含原因），调用方可给用户可见提示。
     * v0.107.0 P0-9：之前 0 频道静默无提示。
     */
    suspend fun refresh(context: Context): List<String> = withContext(Dispatchers.IO) {
        val favs = readFavs(context)
        val out = mutableListOf<LiveChannel>()
        val emptySources = mutableListOf<String>()
        out += readManual(context).map { it.copy(favorite = favs.contains(it.id)) }
        for (s in _sources.value) {
            val text = when (s.type) {
                LiveSource.LOCAL_FILE -> runCatching { File(s.url).readText() }.getOrNull()
                else -> withTimeoutOrNull(15_000) { downloadText(s.url) }
            }
            if (text == null) {
                emptySources += "${s.name}（下载失败）"
                continue
            }
            val key = "m3u:${s.name}"
            val parsed = parseM3u(text, key)
            if (parsed.isEmpty()) emptySources += "${s.name}（未解析出频道）"
            out += parsed.map { it.copy(favorite = favs.contains(it.id)) }
        }
        // live 插件（v0.23.0 安装时已校验 lives 数组）
        StoragePaths.liveDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty().sortedBy { it.name }.forEach { f ->
                val key = "plugin:${f.nameWithoutExtension}"
                out += parseLiveJson(f.readText(), key).map { it.copy(favorite = favs.contains(it.id)) }
            }
        _channels.value = out
        LogBus.i("Live", "聚合 ${out.size} 个频道（手动/订阅/插件）")
        emptySources
    }

    // ---------------- 解析 ----------------

    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")
    private val extinfRe = Regex("""^#EXTINF:[^,]*,(.*)$""")

    /**
     * M3U 解析：#EXTINF 取台名/tvg-logo/group-title，下
     * 一行非 # 开头即播放地址；#EXTM3U 行的 x-tvg-url 做列表级 EPG。
     * 非 #EXTM3U 文本按 TXT 兜底（每行“台名,地址”）。
     */
    fun parseM3u(text: String, sourceKey: String): List<LiveChannel> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        // v0.107.0 P0-9：无 #EXTM3U 头但有 #EXTINF 的也按 M3U 解析（现实中常见）；
        // 之前直接走 parseTxt 兜底，而 parseTxt 会过滤所有 # 开头行 → 确定性 0 频道且静默。
        // 两者都没有才走 TXT 兜底。
        if (lines.none { it.startsWith("#EXTM3U") } && lines.none { it.startsWith("#EXTINF") }) {
            return parseTxt(lines, sourceKey)
        }
        val playlistEpg = lines.firstOrNull()?.let { h ->
            attrRe.findAll(h).firstOrNull { it.groupValues[1] == "x-tvg-url" }
                ?.groupValues?.get(2).orEmpty()
        }.orEmpty()
        val out = mutableListOf<LiveChannel>()
        var i = 0
        while (i < lines.size) {
            val m = extinfRe.matchEntire(lines[i])
            if (m != null) {
                val attrs = attrRe.findAll(lines[i]).associate { it.groupValues[1] to it.groupValues[2] }
                var j = i + 1
                while (j < lines.size && (lines[j].startsWith("#") || lines[j].isBlank())) j++
                val url = if (j < lines.size) lines[j] else ""
                if (url.isNotBlank() && !url.startsWith("#")) {
                    val name = m.groupValues[1].trim().ifBlank { "未知频道" }
                    val id = "$sourceKey|$url"
                    out.add(
                        LiveChannel(
                            id = id, name = name, url = url,
                            logo = attrs["tvg-logo"].orEmpty(),
                            group = attrs["group-title"].orEmpty().ifBlank { "未分组" },
                            ua = attrs["ua"].orEmpty(),
                            epgUrl = attrs["epg"].orEmpty().ifBlank { playlistEpg },
                            sourceKey = sourceKey,
                        ),
                    )
                }
                i = j + 1
            } else i++
        }
        return out
    }

    /** TXT 兜底：每行“台名,地址” */
    private fun parseTxt(lines: List<String>, sourceKey: String): List<LiveChannel> =
        lines.filterNot { it.startsWith("#") }.mapNotNull { line ->
            val parts = line.split(',', '，', ';', '；', limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val name = parts[0].trim()
            val url = parts[1].trim()
            if (name.isBlank() || url.isBlank()) return@mapNotNull null
            LiveChannel(
                id = "$sourceKey|$url", name = name, url = url,
                group = "未分组", sourceKey = sourceKey,
            )
        }

    /** DsPlayer live 插件规范：顶层 lives 数组，{name,url,ua,epg}，url 必填 */
    fun parseLiveJson(text: String, sourceKey: String): List<LiveChannel> {
        return runCatching {
            val root = liveJson.parseToJsonElement(text).jsonObject
            val lives = root["lives"]?.jsonArray ?: return emptyList()
            lives.mapNotNull { el ->
                val o = el.jsonObject
                val url = o["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (url.isBlank()) return@mapNotNull null
                // v0.95.0：过滤已下线的虎牙总站入口（huya:// 无播放器承载，v0.91.0 已删入口；
                // cctv-live 1.0.3 残留的 huya://portal 条目会形成"虎牙直播"空分组）
                if (url.startsWith("huya://", ignoreCase = true)) return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    .ifBlank { "未知频道" }
                LiveChannel(
                    id = "$sourceKey|$url", name = name, url = url,
                    logo = o["logo"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    group = o["group"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "本地源" },
                    ua = o["ua"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    epgUrl = o["epg"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    sourceKey = sourceKey,
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---------------- 持久化 ----------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readSources(context: Context): List<LiveSource> =
        runCatching {
            liveJson.decodeFromString(
                ListSerializer(LiveSource.serializer()),
                prefs(context).getString(KEY_SOURCES, "[]") ?: "[]",
            )
        }.getOrDefault(emptyList())

    private fun persistSources(context: Context) {
        prefs(context).edit()
            .putString(
                KEY_SOURCES,
                liveJson.encodeToString(ListSerializer(LiveSource.serializer()), _sources.value),
            )
            .apply()
    }

    private fun readManual(context: Context): List<LiveChannel> =
        runCatching {
            liveJson.decodeFromString(
                ListSerializer(LiveChannel.serializer()),
                prefs(context).getString(KEY_MANUAL, "[]") ?: "[]",
            )
        }.getOrDefault(emptyList())

    private fun persistManual(context: Context, list: List<LiveChannel>) {
        prefs(context).edit()
            .putString(
                KEY_MANUAL,
                liveJson.encodeToString(ListSerializer(LiveChannel.serializer()), list),
            )
            .apply()
    }

    private fun readFavs(context: Context): Set<String> =
        runCatching {
            liveJson.decodeFromString(
                SetSerializer(String.serializer()),
                prefs(context).getString(KEY_FAVS, "[]") ?: "[]",
            )
        }.getOrDefault(emptySet())

    private fun persistFavs(context: Context, favs: Set<String>) {
        prefs(context).edit()
            .putString(
                KEY_FAVS,
                liveJson.encodeToString(SetSerializer(String.serializer()), favs),
            )
            .apply()
    }

    private fun downloadText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
            .build()
        CmsApi.defaultClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("空响应")
        }
    }

}
