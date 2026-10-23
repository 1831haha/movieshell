package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    /**
     * v0.188.0 M29：过期标记。刷新失败的源不再直接消失，旧频道保留
     * 并标 stale=true（UI 侧据此展示“过期”徽标，见任务说明；字段带
     * 默认值，老缓存 JSON 缺字段时照常解析）。下次该源刷新成功即被
     * 新频道替换，stale 自然清除。
     */
    val stale: Boolean = false,
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
    private const val KEY_GLOBAL_EPG = "live_global_epg"

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels

    private val _sources = MutableStateFlow<List<LiveSource>>(emptyList())
    val sources: StateFlow<List<LiveSource>> = _sources

    // v0.158.0：全局节目单兜底（用户自填 XMLTV 地址，不内置任何源）；
    // 本源无 epg 时用它查正在播/下一档，填空则不兜底。
    private val _globalEpg = MutableStateFlow("")
    val globalEpg: StateFlow<String> = _globalEpg

    private var inited = false

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        _sources.value = readSources(context)
        _globalEpg.value = prefs(context).getString(KEY_GLOBAL_EPG, "").orEmpty()
        // v0.188.0 M29：EPG 磁盘缓存目录（私有 filesDir，无需存储权限）。
        EpgFetcher.setCacheDir(File(context.filesDir, "epg_cache").also { it.mkdirs() })
    }

    fun globalEpgUrl(context: Context): String =
        prefs(context).getString(KEY_GLOBAL_EPG, "").orEmpty()

    fun setGlobalEpgUrl(context: Context, url: String) {
        val v = url.trim()
        _globalEpg.value = v
        prefs(context).edit().putString(KEY_GLOBAL_EPG, v).apply()
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

    /** 单源拉取结果：channels=null 表示下载/解析失败（failReason 带原因）。 */
    private data class SourceFetch(
        val key: String,
        val channels: List<LiveChannel>?,
        val failReason: String?,
    )

    /** 频道列表落盘缓存文件（私有 filesDir，可再生数据，损坏不保全）。 */
    private fun channelsCacheFile(context: Context): File =
        File(context.filesDir, "live_channels_cache.json")

    /** 冷启动/内存为空时先读盘顶上，刷新完成后再替换；损坏返回 null。 */
    private fun loadCachedChannels(context: Context): List<LiveChannel>? {
        val f = channelsCacheFile(context)
        if (!f.isFile) return null
        return runCatching {
            liveJson.decodeFromString(
                ListSerializer(LiveChannel.serializer()),
                f.readText(),
            )
        }.onFailure { LogBus.w("Live", "频道缓存读坏，已忽略：${it.message}") }
            .getOrNull()
    }

    private fun saveCachedChannels(context: Context, list: List<LiveChannel>) {
        runCatching {
            AtomicFile.writeText(
                channelsCacheFile(context),
                liveJson.encodeToString(ListSerializer(LiveChannel.serializer()), list),
            )
        }.onFailure { LogBus.w("Live", "频道缓存写盘失败：${it.message}") }
    }

    /**
     * 重拉所有来源并聚合；IO 线程调用。
     * @return 未解析出频道的订阅名（含原因），调用方可给用户可见提示。
     * v0.107.0 P0-9：之前 0 频道静默无提示。
     * v0.188.0 M29：三点改动——① 订阅并行拉取（Semaphore 限流 4，
     * 与聚合搜索同档，N 个订阅不再 N×15 秒串行）；② 频道列表落盘缓存，
     * 内存为空时先读盘顶上、刷新完写盘；③ 刷新失败的源保留旧频道并标
     * stale=true（过期），不再直接消失；解析出 0 频道同样按失败保留旧频道。
     */
    suspend fun refresh(context: Context): List<String> = withContext(Dispatchers.IO) {
        val favs = readFavs(context)
        // 内存为空时先读盘：进程重建后不等刷新完也有频道可看。
        if (_channels.value.isEmpty()) {
            loadCachedChannels(context)?.let { cached ->
                _channels.value = cached.map { it.copy(favorite = favs.contains(it.id)) }
            }
        }
        // 合并前的旧频道快照（按 sourceKey 分组），失败源从这里取旧频道。
        val oldBySource = _channels.value.groupBy { it.sourceKey }
        val out = mutableListOf<LiveChannel>()
        val emptySources = mutableListOf<String>()
        out += readManual(context).map { it.copy(favorite = favs.contains(it.id), stale = false) }
        val semaphore = Semaphore(4)
        // 订阅列表快照一次：并行任务与合并都用同一份，刷新中途增删源
        // 不会导致任务与结果错位配对（zip 按位置配对，源变了就全错位）。
        val sources = _sources.value
        val fetches = supervisorScope {
            sources.map { s ->
                async {
                    fetchSource(context, s, favs, semaphore)
                }
            }.awaitAll()
        }
        for ((s, fetch) in sources.zip(fetches)) {
            if (fetch.channels != null && fetch.channels.isNotEmpty()) {
                out += fetch.channels
            } else {
                // 下载失败或解析出 0 频道：旧频道保留并标过期，不直接消失。
                val reason = fetch.failReason ?: "未解析出频道"
                emptySources += "${s.name}（$reason）"
                LogBus.e("Live", "订阅 ${s.name} 拉取失败：$reason，地址 ${s.url}，旧频道保留并标过期")
                out += oldBySource[fetch.key].orEmpty()
                    .map { it.copy(favorite = favs.contains(it.id), stale = true) }
            }
        }
        // live 插件（v0.23.0 安装时已校验 lives 数组）
        // v0.173.0：单个插件文件读不出也只跳过，不让 refresh 整体抛。
        StoragePaths.liveDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty().sortedBy { it.name }.forEach { f ->
                val key = "plugin:${f.nameWithoutExtension}"
                val pluginText = runCatching { f.readText() }.getOrNull() ?: return@forEach
                out += parseLiveJson(pluginText, key).map { it.copy(favorite = favs.contains(it.id)) }
            }
        _channels.value = out
        saveCachedChannels(context, out)
        LogBus.i("Live", "聚合 ${out.size} 个频道（手动/订阅/插件）")
        emptySources
    }

    /**
     * 单源拉取（并行任务内执行，Semaphore 限流）。
     * v0.173.0 的单源失败隔离口径不变：下载失败只记原因、不抛异常，
     * 其余正常源不受连坐；协程取消原样抛出，不当成单源失败吞掉。
     */
    private suspend fun fetchSource(
        context: Context,
        s: LiveSource,
        favs: Set<String>,
        semaphore: Semaphore,
    ): SourceFetch {
        val key = "m3u:${s.name}"
        return try {
            semaphore.withPermit {
                var text: String?
                var failReason: String? = null
                try {
                    text = when (s.type) {
                        LiveSource.LOCAL_FILE -> runCatching { File(s.url).readText() }.getOrNull()
                        else -> withTimeoutOrNull(15_000) { downloadText(s.url) }
                    }
                    if (text == null && s.type != LiveSource.LOCAL_FILE) failReason = "超时"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    text = null
                    failReason = e.message ?: e.javaClass.simpleName
                }
                if (text == null) {
                    SourceFetch(key, null, failReason ?: "下载失败")
                } else {
                    val parsed = parseM3u(text, key)
                        .map { it.copy(favorite = favs.contains(it.id), stale = false) }
                    // 解析出 0 频道也按失败处理（channels 为空列表时调用方
                    // 保留旧频道），但要与下载失败区分原因文案。
                    if (parsed.isEmpty()) SourceFetch(key, emptyList(), null)
                    else SourceFetch(key, parsed, null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceFetch(key, null, e.message ?: e.javaClass.simpleName)
        }
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

    /**
     * v0.188.0 M29：与 EpgFetcher 同款可取消调用。单源 15 秒超时此前
     * 取消不掉阻塞中的同步 execute，线程实际仍被占用；现取消即
     * cancel 底层 call，超时真正起效。调用方取消时抛取消，不吞成下载失败。
     */
    private suspend fun downloadText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
            .build()
        val call = CmsApi.defaultClient().newCall(req)
        val job = currentCoroutineContext()[Job]
        job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                return resp.body?.string() ?: throw IllegalStateException("空响应")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 取消时抛回 job 自身的取消异常（超时触发的是
            // TimeoutCancellationException，withTimeoutOrNull 认它才返回 null；
            // 外部取消的 JobCancellationException 原样透传，不当单源失败吞掉）。
            if (job?.isCancelled == true) currentCoroutineContext().ensureActive()
            throw e
        }
    }

}
