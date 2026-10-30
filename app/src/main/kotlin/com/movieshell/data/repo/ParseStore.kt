package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.ParseConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 用户自定义解析接口（v0.19.0）：设置页增删，SharedPreferences 持久化。
 *
 * 生效顺序由 [merged] 统一：自定义优先，其次订阅顶层 parses。
 * 单一条目失败不影响其他（ParseApi 内部已按顺序尝试）。
 */
object ParseStore {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_CUSTOM_PARSES = "custom_parses_json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _custom = MutableStateFlow<List<ParseConfig>>(emptyList())
    val custom: StateFlow<List<ParseConfig>> = _custom.asStateFlow()

    private var loaded = false

    /**
     * v0.161.0：解析失败熔断（进程内，24 小时）。
     * 只熔断“抛异常”的线路（网络超时/服务挂了），空结果不熔断——
     * 空结果可能是该片不支持这条线，不是服务死了，熔断会误伤。
     * 进程重启即清零，不做持久化，避免一次误判永久丢线。
     */
    private const val TRIP_MS = 24L * 60 * 60 * 1000
    // v0.294.7：多条解析并发读写，换 ConcurrentHashMap
    private val trippedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isTripped(url: String): Boolean =
        (trippedUntil[url.trim()] ?: 0L) > System.currentTimeMillis()

    fun markTripped(url: String) {
        trippedUntil[url.trim()] = System.currentTimeMillis() + TRIP_MS
        LogBus.w("ParseStore", "解析线路熔断 24h：$url")
    }

    fun clearTripped(url: String) {
        trippedUntil.remove(url.trim())
        softFails.remove(url.trim())
    }

    /** v0.294.7：按指定时长熔断（软失败累计用，较短） */
    fun markTrippedFor(url: String, ms: Long) {
        trippedUntil[url.trim()] = System.currentTimeMillis() + ms
        LogBus.w("ParseStore", "解析线路连续无结果，暂停 ${ms / 60000} 分钟：$url")
    }

    // ---------------- v0.294.7（M4）：线路成绩（成功率排序） ----------------

    private data class Stat(val ok: Int, val fail: Int, val avgMs: Long)
    private val stats = java.util.concurrent.ConcurrentHashMap<String, Stat>()
    private val softFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private const val SOFT_TRIP_COUNT = 3
    private const val SOFT_TRIP_MS = 30L * 60 * 1000

    /** 成功率得分（拉普拉斯平滑，0..1；越快略加分）。无记录 = 0.5。 */
    fun score(url: String): Double {
        val st = stats[url.trim()] ?: return 0.5
        val rate = (st.ok + 1.0) / (st.ok + st.fail + 2.0)
        val speed = if (st.ok > 0) (1.0 - (st.avgMs.coerceIn(0, 20_000) / 20_000.0)) * 0.1 else 0.0
        return rate + speed
    }

    fun recordSuccess(url: String, costMs: Long) {
        val k = url.trim()
        softFails.remove(k)
        stats.compute(k) { _, old ->
            val o = old ?: Stat(0, 0, 0)
            val avg = if (o.ok == 0) costMs else (o.avgMs * o.ok + costMs) / (o.ok + 1)
            Stat((o.ok + 1).coerceAtMost(1000), o.fail, avg)
        }
        persistStatsLater()
    }

    /** 没拿到地址（超时/空结果）：计失败；连续 3 次暂停 30 分钟（进程内）。 */
    fun recordSoftFail(url: String) {
        val k = url.trim()
        stats.compute(k) { _, old -> val o = old ?: Stat(0, 0, 0); Stat(o.ok, (o.fail + 1).coerceAtMost(1000), o.avgMs) }
        val n = (softFails[k] ?: 0) + 1
        softFails[k] = n
        if (n >= SOFT_TRIP_COUNT) {
            softFails.remove(k)
            markTrippedFor(k, SOFT_TRIP_MS)
        }
        persistStatsLater()
    }

    private const val KEY_STATS = "parse_line_stats_v1"
    @Volatile private var appCtx: Context? = null
    @Volatile private var statsLoaded = false

    private fun loadStats(context: Context) {
        if (statsLoaded) return
        statsLoaded = true
        runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATS, null) ?: return
            raw.split('\n').forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 4) stats[parts[0]] = Stat(parts[1].toInt(), parts[2].toInt(), parts[3].toLong())
            }
        }
    }

    private fun persistStatsLater() {
        val ctx = appCtx ?: return
        runCatching {
            val raw = stats.entries.take(300).joinToString("\n") { (k, v) -> "$k\t${v.ok}\t${v.fail}\t${v.avgMs}" }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATS, raw).apply()
        }
    }

    /**
     * v0.78.0：预装解析线路（用户提供的 16 条）。
     * 仅在用户从未保存过（key 为 null）时 seeding；用户手动清空后不再补。
     * J1（截图截断拿不到完整地址）与 J腾讯关姐（127.0.0.1 本机服务）未收录。
     */
    private val DEFAULT_PARSES = listOf(
        ParseConfig(name = "J2", url = "http://sspa8.top:8100/api/?key=1060089351&url=", type = 1),
        ParseConfig(name = "J芒果4k", url = "http://mg.itufm.top/mg.php?url=", type = 1),
        ParseConfig(name = "W花旗", url = "https://www.huaqi.live/?url=", type = 0),
        ParseConfig(name = "W冰豆", url = "https://bd.jx.cn/?url=", type = 0),
        ParseConfig(name = "W盘古", url = "https://www.playm3u8.cn/jiexi.php?url=", type = 0),
        ParseConfig(name = "W1", url = "https://jx.xymp4.cc/?url=", type = 0),
        ParseConfig(name = "W3", url = "https://yparse.ik9.cc/index.php?url=", type = 0),
        ParseConfig(name = "W4", url = "https://jiexi.site/?url=", type = 0),
        ParseConfig(name = "W5", url = "https://jx.m3u8.tv/jiexi/?url=", type = 0),
        ParseConfig(name = "W7", url = "https://www.pangujiexi.com/jiexi/?url=", type = 0),
        ParseConfig(name = "W8", url = "https://www.pouyun.com/?url=", type = 0),
        ParseConfig(name = "W9", url = "https://jx.xmflv.com/?url=", type = 0),
        ParseConfig(name = "Wa", url = "https://jx.xmflv.cc/?url=", type = 0),
        ParseConfig(name = "Wb", url = "https://jx.yparse.com/index.php?url=", type = 0),
        ParseConfig(name = "Wc", url = "https://www.8090g.cn/?url=", type = 0),
        ParseConfig(name = "Wz", url = "https://www.ckplayer.vip/jiexi/?url=", type = 0),
    )

    /** 懒加载：首次调用时从 prefs 读入；从未保存过则写入预装线路。 */
    fun load(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
        loadStats(context)
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PARSES, null)
        if (raw == null) {
            persist(context, DEFAULT_PARSES)
            LogBus.i("ParseStore", "首次加载，写入预装解析线路 ${DEFAULT_PARSES.size} 条")
            return
        }
        _custom.value = runCatching {
            if (raw.isBlank()) emptyList()
            else json.decodeFromString(ListSerializer(ParseConfig.serializer()), raw)
        }.getOrDefault(emptyList())
            // 读侧按 url 去重：url 是解析线路的业务主键（merged 同口径），
            // 旧文件若存有同 url 两条，设置页按 url 做 lazy key 会撞车崩溃
            .distinctBy { it.url.trim() }
    }

    private fun persist(context: Context, list: List<ParseConfig>) {
        _custom.value = list
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(
                    KEY_CUSTOM_PARSES,
                    json.encodeToString(ListSerializer(ParseConfig.serializer()), list)
                )
                .apply()
        }.onFailure { LogBus.w("ParseStore", "自定义解析持久化失败：${it.message}") }
    }

    /** 新增一条（name/url 非空，type 只认 0/1）；同 url 已存在时原地更新名称/类型，不追加重复条。 */
    /** v0.217.0（F62）：返回是否真加上了——原来空名／空地址静默 return，
     * 页面又不管成没成一律关对话框，用户只填了名点添加、框没了列表里
     * 也没有新线路，完全不知道问题出在哪。 */
    fun add(context: Context, name: String, url: String, type: Int): Boolean {
        load(context)
        val n = name.trim()
        val t = if (type == 99) 99 else type.coerceIn(0, 1)
        // v0.203.0：type=99 的 url 不作接口地址，只作线路标识；留空时补默认值
        val u = url.trim().ifBlank { if (t == 99) "ytdlp://generic" else "" }
        if (n.isEmpty() || u.isEmpty()) return false
        val entry = ParseConfig(name = n, url = u, type = t)
        val cur = _custom.value
        val idx = cur.indexOfFirst { it.url.trim() == u }
        persist(
            context,
            if (idx >= 0) cur.toMutableList().also { it[idx] = entry } else cur + entry,
        )
        LogBus.i("ParseStore", "新增自定义解析：$n")
        return true
    }

    /** 按下标删除。 */
    fun remove(context: Context, index: Int) {
        load(context)
        val cur = _custom.value
        if (index !in cur.indices) return
        persist(context, cur.filterIndexed { i, _ -> i != index })
        LogBus.i("ParseStore", "删除自定义解析：${cur[index].name}")
    }

    /**
     * 生效的解析线路：自定义优先 + 订阅 parses 去重（按 url 去重，自定义已覆盖的不重复加）。
     * 解析链路的唯一合并入口。
     */
    fun merged(context: Context, subParses: List<ParseConfig>): List<ParseConfig> {
        load(context)
        val custom = _custom.value
        if (custom.isEmpty()) return subParses
        val seen = custom.map { it.url.trim() }.toMutableSet()
        return custom + subParses.filter { seen.add(it.url.trim()) }
    }
}
