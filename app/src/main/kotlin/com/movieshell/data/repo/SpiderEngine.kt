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
)

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
    private val instances = ConcurrentHashMap<String, Spider>()

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
     * v0.9.0 修：Android 拒绝从 world-writable 目录（xiaying/jar/ 对所有应用可写）
     * 加载 dex（"Writable dex file ... is not allowed"），加载前 copy 到私有目录。
     * 幂等：目标存在且大小一致则跳过；失败记日志返回 null。
     */
    fun privateJarFile(filesDir: File, src: File): File? {
        return runCatching {
            val dst = privateJarTarget(filesDir, src.name)
            val len = src.length()
            if (len > 0 && dst.isFile && dst.length() == len) return dst
            src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
            dst
        }.onFailure { LogBus.e(TAG, "jar 拷入私有目录失败 ${src.name}：${it.message}") }
            .getOrNull()
    }

    /**
     * 取（或加载）该站的 spider 实例。jar 下载/init 失败返回 null。
     * 必须在 IO 线程调用。
     */
    private fun getSpider(context: Context, site: Site): Spider? {
        instances[instanceKey(site)]?.let { return it }
        val subUrl = site.subUrl
        if (subUrl.isBlank()) {
            LogBus.w(TAG, "站点无订阅 URL，无法定位 jar：${site.name}")
            return null
        }
        val jarUrl = SubSiteStore.getSpiderJar(context, subUrl)
            .ifBlank { SubSiteStore.ensureSpiderJar(context, subUrl) }
        val jar = JarSpiderStore.ensureJar(context, subUrl, jarUrl) ?: return null
        // v0.9.0：dex 必须从私有目录加载（公开目录 world-writable 会被系统拒绝）
        val dexJar = privateJarFile(context.filesDir, jar) ?: return null
        return runCatching {
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
                ?: throw IllegalStateException("不是 Spider 子类")
            spider.init(context.applicationContext, site.ext)
            instances[instanceKey(site)] = spider
            LogBus.i(TAG, "spider 加载成功：${site.api}")
            spider
        }.onFailure { LogBus.e(TAG, "spider 加载失败 ${site.api}：${it.message}") }
            .getOrNull()
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

    suspend fun play(site: Site, flag: String, id: String): SpiderPlayResult =
        withContext(Dispatchers.IO) {
            val sp = getSpider(requireContext(), site) ?: return@withContext SpiderPlayResult()
            runCatching { parsePlayJson(sp.playerContent(flag, id, emptyList())) }
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
        val parseRaw = (o["parse"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val header = (o["header"] as? JsonObject)
            ?.entries?.associate { (k, v) -> k to (v as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty() }
            .orEmpty()
        SpiderPlayResult(
            url = opt("url").ifBlank { opt("playUrl") },
            parse = parseRaw?.toIntOrNull() ?: 0,
            header = header,
        )
    }.getOrDefault(SpiderPlayResult())
}
