package com.movieshell.data.repo

import android.content.Context
import com.github.catvod.crawler.Spider
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.source.CmsApi
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** spider playerContent 返回的播放信息（字段与 Type4PlayResult 对齐）。 */
data class SpiderPlayResult(
    val url: String = "",
    val parse: Int = 0,
    val header: Map<String, String> = emptyMap(),
    /** v0.294.7：jx=1 走解析线路；playUrl 是 TVBox 解析前缀（json:/parse:/解析页地址） */
    val jx: Int = 0,
    val playUrl: String = "",
) {
    fun toRaw() = com.movieshell.data.source.RawPlay(url = url, parse = parse, jx = jx, header = header, playUrl = playUrl)
}

/**
 * v0.8.0：Java spider（csp_ 站）运行时。
 *
 * - 按订阅下载 jar（JarSpiderStore），DexClassLoader 动态加载
 *   `com.github.catvod.spider.Xxx`（csp_ 前缀去掉即类名）；
 * - 基类 com.github.catvod.crawler.Spider 由 App 提供（包名必须一致）；
 * - spider 方法都是同步网络，必须在 IO 线程调；
 * - 返回的 JSON 与苹果 CMS 同构，复用 CmsApi 的解析；
 * - 单个站加载/初始化失败只记日志返回空，不崩，SiteRepository 层兜底。
 */
object SpiderEngine {
    private const val TAG = "SpiderEngine"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var appContext: Context? = null
    private val cmsApi = CmsApi()
    private val loaders = ConcurrentHashMap<String, DexClassLoader>()
    private val instances = ConcurrentHashMap<String, Spider?>()
        // v0.112.0 修：D1 初版用 ConcurrentHashMap<String, Spider> 配 computeIfAbsent，
        // Java 的 Function<? super K, ? extends V> 在 Kotlin 侧映射成返回非空 Spider 的 SAM，
        // lambda 里 return@computeIfAbsent null 编译不过。值类型放宽为 Spider? 后，
        // Java 语义"mapping 返回 null 则不缓存"依然成立（ConcurrentHashMap.computeIfAbsent 规范），
        // 加载失败下次照常重试，调用方本来就按 Spider? 处理。

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            com.github.catvod.crawler.SpiderApi.init(context.applicationContext)
        }
    }

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("SpiderEngine 未 init")

    /** csp_Xxx -> com.github.catvod.spider.Xxx（纯函数，可单测）。 */
    fun classNameForApi(api: String): String {
        val name = api.trim()
            .removePrefix("csp_").removePrefix("Csp_").removePrefix("CSP_")
        return "com.github.catvod.spider.$name"
    }

    private fun instanceKey(site: Site): String = "${site.subUrl}|${site.api.trim()}"

    /** 私有 jar 目标文件（纯函数，可单测；会建目录）。 */
    fun privateJarTarget(filesDir: File, jarName: String): File =
        File(File(filesDir, "spider").apply { mkdirs() }, jarName)

    /**
     * v0.10.1 修：Android 14+（targetSdk 34 起强制）DexClassLoader 拒绝加载"自身可写"的
     * dex——AOSP 逻辑是 access(path, W_OK)==0 即拒（dalvik_system_DexFile.cc），跟目录无关。
     * 所以 copy 到私有目录后必须 setReadOnly()；刷新覆盖前先 setWritable(true)。
     * 幂等：目标存在且大小一致则跳过（并补 setReadOnly）；失败记日志返回 null。
     * v0.246.0：只比大小不够——同长度不同内容的 JAR 会被跳过、继续用旧版。
     * 改大小 + SHA-256 双重判断，任一不同都重拷。
     * S2 修正 v0.246.0 的自动重拷：公开 jar 一变就重拷等于把公开目录
     * 当执行基准，第三方改写公开 jar 后新内容会直接执行。本版改走
     * ExecutableTrust 注册表：首次拷入时锚定摘要，公开 jar 变了且
     * 不是显式刷新时，仍用旧私有副本并记警告，不执行新内容；
     * 私有副本本身与注册表不符时返回 null 拒绝加载。
     * 显式刷新走 evict()（清私有副本与注册表），下次加载重新锚定。
     */
    fun privateJarFile(filesDir: File, src: File): File? {
        return runCatching {
            val dst = privateJarTarget(filesDir, src.name)
            val key = "jar:${src.name}"
            val registered = ExecutableTrust.registeredSha(filesDir, key)
            if (registered == null) {
                dst.setWritable(true)
                src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
                if (!dst.setReadOnly()) LogBus.w(TAG, "jar 设只读失败 ${dst.name}")
                ExecutableTrust.record(filesDir, key, dst)
                return dst
            }
            val dstSha = if (dst.isFile) ExecutableTrust.sha256(dst) else null
            if (dstSha == registered) {
                val srcSha = ExecutableTrust.sha256(src)
                if (srcSha != null && srcSha != registered) {
                    LogBus.w(
                        TAG,
                        "公开 jar 已变动，未自动更新，仍用旧可信版本 ${src.name}",
                    )
                }
                if (!dst.setReadOnly()) LogBus.w(TAG, "jar 设只读失败 ${dst.name}")
                return dst
            }
            if (!dst.isFile) {
                // 私有副本已被显式清掉（evict），公开文件即新的导入源，重新锚定。
                dst.setWritable(true)
                src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
                if (!dst.setReadOnly()) LogBus.w(TAG, "jar 设只读失败 ${dst.name}")
                ExecutableTrust.record(filesDir, key, dst)
                return dst
            }
            LogBus.e(TAG, "jar 私有副本与信任注册表不一致，拒绝加载 ${src.name}")
            null
        }.onFailure { LogBus.e(TAG, "jar 拷入私有目录失败 ${src.name}：${it.message}") }
            .getOrNull()
    }

    /**
     * v0.13.0：解析 spider ext 中的相对路径。
     * 如 ext="./config/blys.json"，按订阅 URL 的目录解析为绝对 URL。
     * ext 为绝对 URL 或非路径格式时原样返回。
     */
    private fun resolveExt(ext: String, subUrl: String): String {
        if (ext.isBlank()) return ext
        // 已是绝对 URL，直接返回
        if (ext.startsWith("http://", ignoreCase = true) ||
            ext.startsWith("https://", ignoreCase = true)) {
            return ext
        }
        // 相对路径：按订阅 URL 的目录解析
        if (ext.startsWith("./") || ext.startsWith("../") || !ext.contains(":")) {
            return runCatching {
                val base = java.net.URL(subUrl)
                val basePath = base.path.substringBeforeLast("/", "")
                val resolvedPath = if (ext.startsWith("./")) {
                    "$basePath/${ext.removePrefix("./")}"
                } else {
                    "$basePath/$ext"
                }
                java.net.URL(base.protocol, base.host, base.port, resolvedPath).toString()
            }.getOrDefault(ext)
        }
        return ext
    }

    /**
     * 取（或加载）该站的 spider 实例。jar 下载/init 失败返回 null。
     * 必须在 IO 线程调用。
     */
    private fun getSpider(context: Context, site: Site): Spider? {
        val subUrl = site.subUrl
        if (subUrl.isBlank()) {
            LogBus.w(TAG, "站点无订阅 URL，无法定位 jar：${site.name}")
            return null
        }
        // v0.112.0 D1：之前"先查后放"非原子，并发下同一站点可能双建 spider
        //（init 两次、后建覆盖先建）。改 computeIfAbsent 原子化，选此方案的理由：
        // ConcurrentHashMap 在 Java 8+ 是按 bin（键）加锁而非整表/段锁，
        // jar 下载/dex 加载的耗时 IO 只阻塞同一站点 key 的并发请求，不同站点互不影响；
        // synchronized 整表锁反而会把其他站点的加载一起串行化。mapping 返回 null 或抛异常时
        // 不缓存，失败下次重试；lambda 内不再手写 put，避免递归更新同一张表。
        return instances.computeIfAbsent(instanceKey(site)) {
            val jarUrl = SubSiteStore.getSpiderJar(context, subUrl)
                .ifBlank { SubSiteStore.ensureSpiderJar(context, subUrl) }
            val jar = JarSpiderStore.ensureJar(context, subUrl, jarUrl)
                ?: return@computeIfAbsent null
            // v0.10.1：jar 必须只读（privateJarFile 内已 setReadOnly），否则 Android 14+ 拒绝加载
            val dexJar = privateJarFile(context.filesDir, jar) ?: return@computeIfAbsent null
            runCatching {
                val loader = loaders.getOrPut(dexJar.absolutePath) {
                    val dexOut = File(context.filesDir, "spider/opt").apply { mkdirs() }
                    DexClassLoader(
                        dexJar.absolutePath,
                        dexOut.absolutePath,
                        null,
                        Spider::class.java.classLoader,
                    )
                }
                val clazz = loader.loadClass(classNameForApi(site.api))
                val spider = clazz.getDeclaredConstructor().newInstance() as? Spider
                    // v0.42.0：带上实际父类名，R8 误改宿主 Spider 类名时一眼定位
                    ?: throw IllegalStateException(
                        "不是 Spider 子类（${clazz.name} 实际父类=${clazz.superclass?.name}，" +
                            "若父类不是 com.github.catvod.crawler.Spider，请检查 proguard keep 规则）",
                    )
                // v0.13.0：ext 相对路径（如 ./config/blys.json）按订阅 base 解析为绝对 URL
                val resolvedExt = resolveExt(site.ext, subUrl)
                spider.init(context.applicationContext, resolvedExt)
                LogBus.i(TAG, "spider 加载成功：${site.api}")
                spider
            }.onFailure { LogBus.e(TAG, "spider 加载失败 ${site.api}：${it.message}") }
                .getOrNull()
        }
    }

    suspend fun home(site: Site): List<Vod> = withContext(Dispatchers.IO) {
        val sp = getSpider(requireContext(), site) ?: return@withContext emptyList()
        runCatching { cmsApi.parseListJson(sp.homeContent(false)) }
            .onFailure { LogBus.e(TAG, "home 失败 ${site.name}：${it.message}") }
            .getOrDefault(emptyList())
    }

    suspend fun category(site: Site, tid: String, pg: Int): Pair<List<CategoryType>, List<Vod>> =
        withContext(Dispatchers.IO) {
            val sp = getSpider(requireContext(), site)
                ?: return@withContext emptyList<CategoryType>() to emptyList()
            runCatching {
                val text = sp.categoryContent(tid, pg.toString(), false, hashMapOf())
                cmsApi.parseClassJson(text) to cmsApi.parseListJson(text)
            }.onFailure { LogBus.e(TAG, "category 失败 ${site.name}：${it.message}") }
                .getOrDefault(emptyList<CategoryType>() to emptyList())
        }

    suspend fun detail(site: Site, vodId: String): VodDetail = withContext(Dispatchers.IO) {
        val sp = getSpider(requireContext(), site) ?: return@withContext VodDetail(vodId = vodId)
        runCatching { cmsApi.parseDetailJson(sp.detailContent(listOf(vodId)), vodId) }
            .onFailure { LogBus.e(TAG, "detail 失败 ${site.name}：${it.message}") }
            .getOrDefault(VodDetail(vodId = vodId))
    }

    suspend fun search(site: Site, wd: String): List<Vod> = withContext(Dispatchers.IO) {
        val sp = getSpider(requireContext(), site) ?: return@withContext emptyList()
        runCatching { cmsApi.parseListJson(sp.searchContent(wd, false)) }
            .onFailure { LogBus.e(TAG, "search 失败 ${site.name}：${it.message}") }
            .getOrDefault(emptyList())
    }

    suspend fun play(
        site: Site,
        flag: String,
        id: String,
        // v0.294.7（M1）：vipFlags 传订阅 flags（无则默认 VIP 表），原来恒为空列表
        vipFlags: List<String> = com.movieshell.data.source.PlayRouting.DEFAULT_VIP_FLAGS,
    ): SpiderPlayResult =
        withContext(Dispatchers.IO) {
            val sp = getSpider(requireContext(), site) ?: return@withContext SpiderPlayResult()
            runCatching { parsePlayJson(sp.playerContent(flag, id, vipFlags)) }
                .onFailure { LogBus.e(TAG, "play 失败 ${site.name}：${it.message}") }
                .getOrDefault(SpiderPlayResult())
        }

    /**
     * 订阅刷新后清掉该订阅的 spider 实例与 jar 缓存，下次使用时重下
     *（jar 内容可能更新但文件名不变，不能只看文件名）。
     */
    fun evict(context: Context, subUrl: String) {
        instances.keys.removeIf { it.startsWith("$subUrl|") }
        val prefix = SubSiteStore.subId(subUrl) + "_"
        runCatching {
            // 公开缓存
            StoragePaths.jarDir(context).listFiles { f -> f.name.startsWith(prefix) }
                ?.forEach { f ->
                    loaders.remove(f.absolutePath)
                    f.delete()
                }
            // v0.9.0：私有目录的 copy 一起清（loaders 以私有路径为 key）
            File(context.filesDir, "spider").listFiles { f -> f.name.startsWith(prefix) }
                ?.forEach { f ->
                    loaders.remove(f.absolutePath)
                    ExecutableTrust.clear(context.filesDir, "jar:${f.name}")
                    f.delete()
                }
        }
        LogBus.i(TAG, "spider 缓存已清：$subUrl")
    }

    /**
     * 解析 playerContent 的返回：标准是
     * {"parse":0,"url":"...","header":{...}}；少数 spider 直接返回裸 url。
     * 纯函数，可单测。
     */
    fun parsePlayJson(text: String): SpiderPlayResult = runCatching {
        val t = text.trim()
        if (!t.startsWith("{")) {
            // 裸 url 直接透传；其余非 JSON 文本视为无效
            return if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("magnet:")) {
                SpiderPlayResult(url = t)
            } else {
                SpiderPlayResult()
            }
        }
        val o = json.parseToJsonElement(t).jsonObject
        fun opt(key: String): String =
            (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        // parse 可能是数字 1 也可能是字符串 "1"，两种都认
        fun optInt(key: String): Int =
            (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.toIntOrNull() ?: 0
        // v0.294.7：header 值为数字/布尔、或整个 header 是 JSON 字符串时也认（原来非字符串值被丢成空）
        val header = com.movieshell.player.PlayHeaders.fromJson(o["header"] ?: o["headers"])
        // v0.294.7（Bug7）：playUrl 是 TVBox 的解析前缀，不是播放地址；只有 url 为空且
        // playUrl 本身就是媒体直链时才按旧行为当地址用（兼容少数把直链写进 playUrl 的 spider）。
        var url = opt("url")
        var playUrl = opt("playUrl")
        if (url.isBlank() && playUrl.isNotBlank() &&
            com.movieshell.data.source.PlayRouting.isMediaUrl(playUrl)
        ) {
            url = playUrl
            playUrl = ""
        }
        SpiderPlayResult(
            url = url,
            parse = optInt("parse"),
            header = header,
            jx = optInt("jx"),
            playUrl = playUrl,
        )
    }.getOrDefault(SpiderPlayResult())
}
