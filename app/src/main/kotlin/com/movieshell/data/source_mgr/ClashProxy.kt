package com.movieshell.data.source_mgr

import android.content.Context
import android.content.SharedPreferences
import com.movieshell.core.log.LogBus
import com.movieshell.core.net.ProxyRouting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * v1.3.26：clash 插件 App 侧管理器。
 *
 * 插件本体（mihomo 二进制 + start.sh + server.json）在 ~/workspace/clash-plugin/
 * 打包成 server 插件装进来；这里只管 App 侧四件事：
 * 1. 订阅链接存私有 prefs，更新订阅时带 Clash UA 拉取（面板只认 Clash UA
 *    才回配置，裸请求回空——2026-11-19 沙箱实测），校验后写进私有目录
 *    filesDir/service_tmp/clash/sub.yaml，mihomo 用 file 型 provider 读它。
 * 2. 每源一个“走代理”开关（key 是不透明字符串，由 UI 层按源类型拼，
 *    见 jsKey/pyKey/cmsKey/webKey），存在私有 prefs，不动源记录本身。
 * 3. host 集：开了代理的源的 base host + 运行时从它返回的结果/播放地址里
 *    扫到的 host，按源 key 分组记；生效集 = 所有已开 key 的 host 并集。
 *    ProxyRouting.selector 按这个集决定每条 OkHttp 请求走不走代理。
 * 4. 服务运行态：只认 ServicePluginManager.portOf("clash")，不跑就不代理。
 *
 * 订阅链接与节点凭证只存本机私有目录，不进仓库、不进聊天、不进记忆。
 */
object ClashProxy {

    private const val TAG = "ClashProxy"

    const val PLUGIN_ID = "clash"

    /** 拉订阅用的 UA（实测：面板只认 Clash UA 才回配置）。 */
    const val SUBSCRIPTION_UA = "ClashMeta/1.19.34"

    /** 订阅文件上限 2MB，超了不落盘（防异常回包撑爆）。 */
    private const val MAX_SUB_BYTES = 2 * 1024 * 1024

    private const val PREFS_NAME = "clash_proxy"
    private const val KEY_SUB_URL = "subscription_url"
    private const val KEY_SOURCE_PROXY = "source_proxy"
    private const val KEY_HOSTS_BY_SOURCE = "hosts_by_source"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    /** 源 key → 该源贡献的 host 集（内存镜像，随改随存）。 */
    private val hostsBySource = ConcurrentHashMap<String, MutableSet<String>>()

    /** 生效 host 集快照（所有已开 key 的并集），selector 每请求读它。 */
    @Volatile
    private var hostsSnapshot: Set<String> = emptySet()

    // ---------------- 初始化 ----------------

    /**
     * App 启动时调一次（XiaYingApp.onCreate，与 HttpClient.initCookies 同处）。
     * 把路由回调注入 ProxyRouting；注入前默认全部直连。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadHostsBySource()
        rebuildSnapshot()
        ProxyRouting.shouldProxyHost = { host -> isRunning() && matchesHost(host) }
        ProxyRouting.proxyAddress = { mixedPort()?.let { InetSocketAddress("127.0.0.1", it) } }
    }

    /**
     * v1.3.28：卸载 clash 时一并清除订阅链接、每源代理开关与 host 集
     * （卸载即移除全部数据；重装后重新粘贴订阅、重新开开关）。内存镜像
     * 也清掉，否则路由还按旧 host 集走代理。
     */
    fun clearAll() {
        if (::prefs.isInitialized) prefs.edit().clear().apply()
        hostsBySource.clear()
        hostsSnapshot = emptySet()
    }

    // ---------------- 服务运行态 ----------------

    /** clash 服务是否在跑（只认运行中端口，没跑就不代理）。 */
    fun isRunning(): Boolean = runCatching { ServicePluginManager.portOf(PLUGIN_ID) != null }.getOrDefault(false)

    /** 服务是否已安装（注册表里有没有 clash）。 */
    fun isInstalled(context: Context): Boolean =
        runCatching { ServicePluginManager.installedPlugins(context).any { it.id == PLUGIN_ID } }.getOrDefault(false)

    /** 控制 API 端口（= 分配到的端口，健康检查 GET /version 走这里）。 */
    fun controllerPort(): Int? = ServicePluginManager.portOf(PLUGIN_ID)

    /** 混合代理端口（控制端口 +1，见插件 README 的端口约定）。 */
    fun mixedPort(): Int? = controllerPort()?.plus(1)

    /** 订阅文件目录（私有目录，服务启动时 TMPDIR 指向的 service_tmp 下）。 */
    fun subscriptionDir(): File = File(File(appContext.filesDir, "service_tmp"), "clash")

    /** mihomo file 型 provider 读的订阅文件。 */
    fun subFile(): File = File(subscriptionDir(), "sub.yaml")

    /** 订阅文件是否已拉取过。 */
    fun hasSubscription(): Boolean = runCatching { subFile().let { it.isFile && it.length() > 0 } }.getOrDefault(false)

    // ---------------- 订阅 ----------------

    fun subscriptionUrl(): String = prefs.getString(KEY_SUB_URL, "").orEmpty()

    /** 存订阅链接（只做格式校验：须是 http(s) 地址）。 */
    fun setSubscriptionUrl(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        prefs.edit().putString(KEY_SUB_URL, u).apply()
        return true
    }

    /**
     * 更新订阅：带 Clash UA 拉取，校验后原子写 sub.yaml。
     * 成功返回节点数（proxies 段里数到的名字个数，仅作状态显示）；
     * 失败抛异常，调用方把 message 展示给用户。
     * 拉取不走代理（直连面板 IP，且不能用代理拉配代理的订阅）。
     */
    suspend fun fetchSubscription(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val url = subscriptionUrl()
            require(url.isNotBlank()) { "请先填订阅链接" }
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", SUBSCRIPTION_UA)
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("订阅请求失败：HTTP ${resp.code}")
                resp.body?.bytes() ?: throw IllegalStateException("订阅返回为空")
            }
            if (body.isEmpty()) throw IllegalStateException("订阅返回为空（面板可能只认 Clash 客户端，请检查链接）")
            if (body.size > MAX_SUB_BYTES) throw IllegalStateException("订阅文件过大（${body.size} 字节），拒绝写入")
            val text = body.toString(Charsets.UTF_8)
            if (!text.contains("proxies:")) throw IllegalStateException("订阅内容不是 Clash 配置（没找到 proxies）")
            val count = countProxies(text)
            val dir = subscriptionDir().apply { mkdirs() }
            val tmp = File(dir, "sub.yaml.tmp")
            tmp.writeBytes(body)
            if (!tmp.renameTo(subFile())) throw IllegalStateException("订阅写入失败")
            LogBus.d(TAG, "订阅已更新，节点约 $count 个")
            count
        }
    }

    /** 数 proxies 段里的节点名（块式 `- name:` 与流式 `- {name:` 都算，仅作状态显示）。 */
    internal fun countProxies(text: String): Int {
        val start = text.indexOf("\nproxies:")
        if (start < 0 && !text.startsWith("proxies:")) return 0
        val from = if (start < 0) 0 else start
        val rest = text.substring(from)
        // 下一个顶层键处截断
        val end = Regex("\n[A-Za-z][A-Za-z0-9_-]*:").find(rest, startIndex = 1)?.range?.first ?: rest.length
        val section = rest.substring(0, end)
        return Regex("""-\s*(?:name:|\{[^}]*\bname\s*:)""").findAll(section).count()
    }

    // ---------------- 每源开关 ----------------

    fun jsKey(ruleName: String): String = "js:$ruleName"

    fun pyKey(ruleName: String): String = "py:$ruleName"

    fun cmsKey(apiUrl: String): String = "cms:$apiUrl"

    fun webKey(siteKey: String): String = "web:$siteKey"

    fun phpKey(ruleName: String): String = "php:$ruleName"

    /** CMS 站点 key（SubSiteStore.siteKey 形如 "sub:<hash>:<站名>"）本身就是开关 key。 */
    fun subKey(siteKey: String): String = siteKey

    fun isSourceProxied(key: String): Boolean = enabledKeys().contains(key)

    fun enabledKeys(): Set<String> = prefs.getStringSet(KEY_SOURCE_PROXY, emptySet()).orEmpty().toSet()

    /**
     * 开/关某源的代理。开时把该源的 base host（调用方传，如规则 homeUrl / CMS 接口地址
     * 的 host）记进该 key 的 host 集；关时只改开关，host 集保留（别的同 host 源可能还在用，
     * 生效集只算已开 key 的并集，关了就不生效）。
     */
    fun setSourceProxied(key: String, on: Boolean, baseUrls: Collection<String> = emptyList()) {
        val keys = enabledKeys().toMutableSet()
        if (on) keys.add(key) else keys.remove(key)
        prefs.edit().putStringSet(KEY_SOURCE_PROXY, keys).apply()
        if (on && baseUrls.isNotEmpty()) registerHosts(key, baseUrls.mapNotNull { hostOf(it) })
        rebuildSnapshot()
    }

    // ---------------- host 集 ----------------

    /** 把一批 host 记到某源 key 名下（规范化：小写、去端口、去末尾点）。 */
    fun registerHosts(sourceKey: String, hosts: Collection<String>) {
        val clean = hosts.mapNotNull { normalizeHost(it) }.toSet()
        if (clean.isEmpty()) return
        val set = hostsBySource.getOrPut(sourceKey) { mutableSetOf() }
        if (set.addAll(clean)) {
            persistHostsBySource()
            rebuildSnapshot()
        }
    }

    /** 从一段文本里扫 http(s) 地址的 host，记到某源 key 名下（引擎结果/播放地址用）。 */
    fun registerUrls(sourceKey: String, text: String) {
        val hosts = Regex("""https?://([A-Za-z0-9._-]+)""").findAll(text)
            .map { it.groupValues[1] }.toList()
        registerHosts(sourceKey, hosts)
    }

    /** 生效 host 集（只读快照，供 UI 显示调试用）。 */
    fun proxiedHosts(): Set<String> = hostsSnapshot

    /** 某 host 是否在生效集里（等于或属于某条目的子域，如 a.b.com 命中 b.com）。 */
    fun matchesHost(host: String): Boolean {
        val h = normalizeHost(host) ?: return false
        return hostsSnapshot.any { h == it || h.endsWith(".$it") }
    }

    private fun rebuildSnapshot() {
        val enabled = enabledKeys()
        hostsSnapshot = hostsBySource.entries
            .filter { enabled.contains(it.key) }
            .flatMap { it.value }.toSet()
    }

    private fun loadHostsBySource() {
        hostsBySource.clear()
        runCatching {
            val root = JSONObject(prefs.getString(KEY_HOSTS_BY_SOURCE, "{}").orEmpty())
            root.keys().forEach { key ->
                val arr = root.optJSONArray(key) ?: return@forEach
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    normalizeHost(arr.optString(i))?.let { set.add(it) }
                }
                if (set.isNotEmpty()) hostsBySource[key] = set
            }
        }.onFailure { LogBus.w(TAG, "host 集读回失败: ${it.message}") }
    }

    private fun persistHostsBySource() {
        runCatching {
            val root = JSONObject()
            hostsBySource.forEach { (k, v) ->
                val arr = org.json.JSONArray()
                v.forEach { arr.put(it) }
                root.put(k, arr)
            }
            prefs.edit().putString(KEY_HOSTS_BY_SOURCE, root.toString()).apply()
        }.onFailure { LogBus.w(TAG, "host 集落盘失败: ${it.message}") }
    }

    /** 从 drpy 规则文本里取 base host（规则对象的 host 字段；取不到返回 null，不猜）。 */
    fun extractRuleHost(ruleJs: String): String? {
        val m = Regex("""['"]?host['"]?\s*:\s*['"](https?://[^'"]+)['"]""").find(ruleJs)
            ?: return null
        return hostOf(m.groupValues[1])
    }

    /** host 规范化：小写、去端口、去末尾点；非法返回 null。 */
    fun normalizeHost(raw: String): String? {
        val h = raw.trim().lowercase().substringBefore(":").trimEnd('.')
        if (h.isEmpty() || !h.contains(".")) return null
        if (h.any { !it.isLetterOrDigit() && it != '.' && it != '-' }) return null
        return h
    }

    /** 从 URL 里取 host（非法返回 null）。 */
    fun hostOf(url: String): String? {
        return runCatching {
            val noScheme = url.substringAfter("://", "")
            if (noScheme.isEmpty() || noScheme == url) return null
            normalizeHost(noScheme.substringBefore("/").substringBefore("?"))
        }.getOrNull()
    }
}
