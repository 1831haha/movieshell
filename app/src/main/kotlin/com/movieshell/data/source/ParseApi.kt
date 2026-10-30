package com.movieshell.data.source

import android.content.Context
import com.movieshell.data.model.ParseConfig
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.ParseStore
import com.movieshell.player.SniffPlayHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

/**
 * 解析链路（v0.7）：把视频地址解析成可播直链。
 *
 * - type=1/2：JSON 接口。GET `parse.url + urlencode(videoUrl)`，带上 parse.header；
 *   返回 JSON 取 `url`（兼容 `data.url` / `data` / `play_url` 等），v0.294.7 起同时读取
 *   JSON 里的 header/headers/user-agent/referer 透传给播放器。
 * - type=3/4：聚合/超级解析标记行（url 是 http 时按 JSON 接口用，否则只作标记跳过）。
 * - type=0：WebView 嗅探（v0.294.7 起走 [AutoSniffer]，与手动嗅探页同一套能力）。
 * - type=99：yt-dlp 通用解析。
 *
 * v0.294.7（H3）：并发——JSON 线路同时发（≤8 并发），嗅探线路最多 2 个 WebView 同时跑，
 * 先拿到的赢、其余取消；整体受 [SniffConfig.parseBudgetMs] 预算约束；yt-dlp 最后兜底。
 * 线路顺序：命中当前 flag 的先、成功率高的先（M1 / M4）；结果 10 分钟缓存（L1）；
 * 进度经 [ParseProgressBus] 发给 UI，可取消 / 指定线路（L2）。
 */
class ParseApi(
    private val client: OkHttpClient = CmsApi.defaultClient()
) {

    companion object {
        private const val TAG = "ParseApi"

        /** JSON 线路单条超时 */
        private const val JSON_TIMEOUT_MS = 12_000L
        /** JSON 线路并发上限 */
        private const val JSON_CONCURRENCY = 8
        /** 嗅探线路并发上限（AutoSniffer 另有全局 2 个 WebView 上限） */
        private const val SNIFF_CONCURRENCY = 2

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** 测活样片（常见 VIP 站剧集页） */
        private const val PROBE_SAMPLE_URL = "https://v.qq.com/x/cover/mzc00200mp8vo9b/n0047j0pi9r.html"

        fun isJsonType(p: ParseConfig): Boolean =
            p.type == 1 || p.type == 2 ||
                ((p.type == 3 || p.type == 4) && p.url.trim().startsWith("http", ignoreCase = true))
    }

    /** 解析命中：直链 + 该直链播放需要的请求头 + 命中线路名 */
    data class ParseHit(val url: String, val headers: Map<String, String>, val line: String)

    /** 本次解析是否被用户取消（UI 点了取消）——调用方据此不弹“解析失败”。 */
    @Volatile
    var cancelledByUser: Boolean = false
        private set

    /**
     * 兼容旧入口：返回第一条抓到的直链；全部失败返回 ""。
     * 命中的请求头已经存进 SniffPlayHeaders（播放器取用）。
     * @param context type=0 嗅探需要 Context 建 WebView；传 null 时跳过所有 type=0 条目
     */
    suspend fun parse(
        parses: List<ParseConfig>,
        videoUrl: String,
        context: Context? = null,
        flag: String = "",
        showProgress: Boolean = false,
    ): String = parseDetailed(parses, videoUrl, context, flag, showProgress)?.url.orEmpty()

    /** v0.294.7：带请求头/线路名的完整结果；全部失败或取消返回 null。 */
    suspend fun parseDetailed(
        parses: List<ParseConfig>,
        videoUrl: String,
        context: Context? = null,
        flag: String = "",
        showProgress: Boolean = true,
    ): ParseHit? = withContext(Dispatchers.Default) {
        // 注：切到 Default 再计时——预算/单条超时都是真实时间（runTest 虚拟时钟下也成立）
        if (parses.isEmpty() || videoUrl.isBlank()) return@withContext null
        val usable = parses.filter { it.url.isNotBlank() || it.type == 99 }
        if (usable.isEmpty()) return@withContext null
        val cacheKey = ParseCache.key(videoUrl, usable.map { it.url })
        ParseCache.get(cacheKey)?.let { c ->
            LogBus.i(TAG, "解析缓存命中（${c.line}）：${c.url.take(100)}")
            if (c.headers.isNotEmpty()) SniffPlayHeaders.put(c.url, c.headers)
            return@withContext ParseHit(c.url, c.headers, c.line)
        }
        val notTripped = usable.filterNot { ParseStore.isTripped(it.url) }
        // 全部熔断时不至于一条都不试：退回全部线路（熔断只是排序提示）
        val pool = notTripped.ifEmpty { usable }
        val ordered = PlayRouting.orderLines(pool, flag, { it.flags }, { ParseStore.score(it.url) })
        LogBus.i(TAG, "开始解析：${ordered.size} 条线路（跳过熔断 ${usable.size - notTripped.size}），url=$videoUrl")
        val session = if (showProgress) ParseProgressBus.begin(ordered) else null
        val hit = try {
            if (session == null) {
                race(ordered, videoUrl, context, null)
            } else {
                coroutineScope {
                    val raceJob = async { race(ordered, videoUrl, context, session) }
                    select<ParseHit?> {
                        raceJob.onAwait { it }
                        session.control.onAwait { c ->
                            raceJob.cancel()
                            when (c) {
                                is ParseProgressBus.Control.Cancel -> {
                                    cancelledByUser = true
                                    LogBus.i(TAG, "用户取消解析")
                                    null
                                }
                                is ParseProgressBus.Control.Pick -> {
                                    LogBus.i(TAG, "用户指定线路：${c.line.name}")
                                    ParseProgressBus.onLineStart(session, c.line, picked = true)
                                    tryLine(c.line, videoUrl, context, null, longer = true)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            session?.let { ParseProgressBus.end(it) }
        }
        if (hit != null) {
            if (hit.headers.isNotEmpty()) SniffPlayHeaders.put(hit.url, hit.headers)
            ParseCache.put(cacheKey, hit.url, hit.headers, hit.line)
            LogBus.i(TAG, "解析成功（${hit.line}）")
        } else if (!cancelledByUser) {
            LogBus.e(TAG, "全部 ${ordered.size} 条线路解析失败")
        }
        hit
    }

    /** 并发竞速：JSON 全发、嗅探 2 并发、yt-dlp 最后；先到先得，总预算到点即止。 */
    private suspend fun race(
        lines: List<ParseConfig>,
        videoUrl: String,
        context: Context?,
        session: ParseProgressBus.Session?,
    ): ParseHit? = withTimeoutOrNull(SniffConfig.parseBudgetMs) {
        coroutineScope {
            val result = CompletableDeferred<ParseHit?>()
            val jobs = ArrayList<Job>()
            val jsonGate = Semaphore(JSON_CONCURRENCY)
            val sniffGate = Semaphore(SNIFF_CONCURRENCY)
            for (p in lines) {
                when {
                    isJsonType(p) -> jobs += launch {
                        jsonGate.withPermit {
                            if (!result.isCompleted) tryLine(p, videoUrl, context, session)?.let { result.complete(it) }
                        }
                    }
                    p.type == 0 && context != null -> jobs += launch {
                        sniffGate.withPermit {
                            if (!result.isCompleted) tryLine(p, videoUrl, context, session)?.let { result.complete(it) }
                        }
                    }
                    else -> Unit
                }
            }
            val yt = lines.filter { it.type == 99 }
            launch {
                jobs.joinAll()
                if (!result.isCompleted) {
                    for (p in yt) {
                        tryLine(p, videoUrl, context, session)?.let { result.complete(it); return@launch }
                    }
                    result.complete(null)
                }
            }
            val r = result.await()
            coroutineContext.cancelChildren()
            r
        }
    }

    /** 跑一条线路：成功记成绩、异常熔断、空结果记软失败；取消原样透传不记。 */
    private suspend fun tryLine(
        p: ParseConfig,
        videoUrl: String,
        context: Context?,
        session: ParseProgressBus.Session?,
        longer: Boolean = false,
    ): ParseHit? {
        session?.let { if (!longer) ParseProgressBus.onLineStart(it, p) }
        val t0 = System.currentTimeMillis()
        val hit = try {
            when {
                isJsonType(p) -> withTimeoutOrNull(if (longer) JSON_TIMEOUT_MS * 2 else JSON_TIMEOUT_MS) { parseJson(p, videoUrl) }
                p.type == 0 -> if (context == null) null else {
                    val target = p.url.trim() + URLEncoder.encode(videoUrl, Charsets.UTF_8.name())
                    AutoSniffer.sniff(
                        context, target, p.header,
                        timeoutMs = if (longer) SniffConfig.autoSniffTimeoutMs * 2 else SniffConfig.autoSniffTimeoutMs,
                        label = "解析线路「${p.name}」",
                    )?.let { ParseHit(it.url, it.headers, p.name) }
                }
                p.type == 99 -> parseType99(videoUrl).takeIf { it.isNotBlank() }?.let { ParseHit(it, emptyMap(), p.name) }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只熔断抛异常的线（网络异常/服务挂了）
            ParseStore.markTripped(p.url)
            LogBus.w(TAG, "线路「${p.name}」失败：${e.message}")
            return null
        }
        if (hit != null && hit.url.isNotBlank()) {
            ParseStore.clearTripped(p.url)
            ParseStore.recordSuccess(p.url, System.currentTimeMillis() - t0)
            return hit
        }
        // 正常结束但没拿到（超时/该片不支持）：软失败，连续多次才短暂停用
        currentCoroutineContext().ensureActive()
        ParseStore.recordSoftFail(p.url)
        return null
    }

    /**
     * v0.188.0 M30：可取消版同步执行。探活 10 秒、嗅探 20 秒本就小于
     * 底层 60 秒 callTimeout，但同步 execute 原先不响应协程取消，超时
     * 后线程仍占着跑；现取消即 cancel 底层 call。取消时抛回 job 自身
     * 的取消异常：超时触发的是 TimeoutCancellationException，探活的
     * withTimeoutOrNull 认它才返回 false；外部取消的原样透传。
     * 调用方负责 .use 关闭返回的 Response（与原来 execute().use 同款）。
     */
    private suspend fun executeCancellable(call: Call): Response {
        val job = currentCoroutineContext()[Job]
        job?.invokeOnCompletion { call.cancel() }
        try {
            return call.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (job?.isCancelled == true) currentCoroutineContext().ensureActive()
            throw e
        }
    }

    /**
     * 测活（v0.78.0）：快速判断一条解析线路是否可用，10 秒超时。
     * - type=1（JSON）：GET `url + 测试地址`，返回合法 JSON 即算存活
     *   （即使是报错 JSON，也说明服务本身在）。
     * - type=0（嗅探）：GET 解析页基地址，HTTP 200 即算存活
     *   （不做完整 WebView 嗅探，那个太慢；只验域名/服务在不在）。
     */
    suspend fun probe(parse: ParseConfig, context: Context? = null): Boolean {
        // v0.294.7（L5）：嗅探线路有 Context 时做一次真实嗅探（样片地址，≤20 秒）——
        // 原来只看解析页 HTTP 200，页面活着但解析不出也算“可用”。
        if (parse.type == 0 && context != null && parse.url.isNotBlank()) {
            val target = parse.url.trim() + URLEncoder.encode(PROBE_SAMPLE_URL, Charsets.UTF_8.name())
            val hit = runCatching {
                AutoSniffer.sniff(context, target, parse.header, timeoutMs = 20_000L, label = "测活「${parse.name}」")
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (hit != null) ParseStore.clearTripped(parse.url)
            return hit != null
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
            withTimeoutOrNull(10_000L) {
                when (parse.type) {
                    1 -> {
                        val base = parse.url.trim()
                        if (base.isBlank()) return@withTimeoutOrNull false
                        val full = base + URLEncoder.encode(
                            "https://v.qq.com/x/cover/test.html", Charsets.UTF_8.name()
                        )
                        val builder = Request.Builder().url(full).get()
                        parse.header.forEach { (k, v) ->
                            if (k.isNotBlank() && v.isNotBlank()) {
                                runCatching { builder.header(k, v) }
                            }
                        }
                        executeCancellable(client.newCall(builder.build())).use { resp ->
                            if (!resp.isSuccessful) return@withTimeoutOrNull false
                            val body = resp.body?.string().orEmpty().trim()
                            runCatching { json.parseToJsonElement(body).jsonObject }
                                .isSuccess
                        }
                    }
                    else -> {
                        val base = parse.url.trim()
                        if (base.isBlank()) return@withTimeoutOrNull false
                        val builder = Request.Builder().url(base).get()
                        executeCancellable(client.newCall(builder.build())).use { resp ->
                            resp.isSuccessful
                        }
                    }
                }
            } ?: false
            }.getOrDefault(false)
        }
        // v0.161.0：设置页测活通过，说明服务活了，解除熔断
        if (ok) ParseStore.clearTripped(parse.url)
        return ok
    }

    /**
     * JSON 线路：GET `parse.url + urlencode(videoUrl)`，带 parse.header；
     * 结果取地址与请求头（[extractHit]）。
     */
    private suspend fun parseJson(parse: ParseConfig, videoUrl: String): ParseHit? =
        withContext(Dispatchers.IO) {
            val base = parse.url.trim()
            if (base.isBlank()) return@withContext null
            val full = base + URLEncoder.encode(videoUrl, Charsets.UTF_8.name())
            val builder = Request.Builder().url(full).get()
            parse.header.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) {
                    runCatching { builder.header(k, v) }
                }
            }
            executeCancellable(client.newCall(builder.build())).use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val (url, headers) = extractHit(resp.body?.string().orEmpty(), videoUrl) ?: return@withContext null
                ParseHit(url, headers, parse.name.ifBlank { "JSON" })
            }
        }

    /**
     * v0.203.0：type=99 yt-dlp 通用解析。插件未装时 ChaquopyEngine 返回空串，
     * 本函数不抛异常，因此不触发 ParseApi 的熔断（装上插件后本线路立刻可用）。
     */
    private suspend fun parseType99(videoUrl: String): String {
        val hit = ChaquopyEngine.resolveYtGeneric(videoUrl)
        if (hit.isNotBlank()) LogBus.i(TAG, "yt-dlp 通用解析成功")
        return hit
    }

    /**
     * 纯函数（可单元测试）：解析线路返回的 JSON，取顶层 `url`，
     * 兼容 `data.url` 包络；取不到返回 ""。
     */
    fun extractUrl(text: String): String = extractHit(text, "")?.first.orEmpty()

    /**
     * v0.294.7（M2，纯函数）：JSON 解析结果 → (直链, 请求头)。
     * 地址字段：url / data.url / data(字符串) / play_url / playUrl / m3u8；`//host` 补 https；
     * 必须是 http(s)、不能等于原视频地址、不能还是 VIP 站网页。
     * 请求头：header / headers（对象或 JSON 字符串）+ 顶层 user-agent / referer / cookie。
     */
    fun extractHit(text: String, input: String): Pair<String, Map<String, String>>? = runCatching {
        val root = json.parseToJsonElement(text.trim()).jsonObject
        val data = root["data"]
        val dataObj = data as? JsonObject
        val candidates = listOf(
            root.stringField("url"),
            dataObj?.stringField("url").orEmpty(),
            (data as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty(),
            root.stringField("play_url"), root.stringField("playUrl"), root.stringField("m3u8"),
            dataObj?.stringField("play_url").orEmpty(),
        )
        val url = candidates.map { it.trim() }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .firstOrNull { c ->
                (c.startsWith("http://", true) || c.startsWith("https://", true)) &&
                    c != input.trim() && !PlayRouting.isVipUrl(c)
            } ?: return null
        val h = LinkedHashMap<String, String>()
        h.putAll(com.movieshell.player.PlayHeaders.fromJson(root["header"] ?: root["headers"] ?: dataObj?.get("header")))
        for (k in listOf("user-agent", "User-Agent", "UA", "referer", "Referer", "cookie", "Cookie")) {
            root.stringField(k).takeIf { it.isNotBlank() }?.let { v -> h[k] = v }
        }
        url to com.movieshell.player.PlayHeaders.normalize(h)
    }.getOrNull()

    /** 兼容字段值为数字 / 布尔 / 字符串的写法；缺失或 null 返回空串。 */
    private fun JsonObject.stringField(key: String): String =
        ((this[key] as? JsonPrimitive)?.contentOrNull).orEmpty().trim()
}
