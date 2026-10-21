package com.movieshell.data.model

import kotlinx.serialization.Serializable

/**
 * 多仓订阅的子仓条目。
 * 多仓地址返回的不再是完整 TVBox 配置，而是一组子订阅地址列表，
 * JSON 形态 {"urls":[{"name":"...","url":"..."}]} 或 TXT 纯 URL 行。
 */
@Serializable
data class WarehouseEntry(
    val name: String = "",
    val url: String = "",
)

/**
 * TVBox 标准订阅 JSON 顶层结构。
 * 对应订阅源形如：{ "sites": [...], "parses": [...], "flags": [...], "spider": "..." }
 */
@Serializable
data class TvBoxConfig(
    val sites: List<SiteConfig> = emptyList(),
    val parses: List<ParseConfig> = emptyList(),
    val flags: List<String> = emptyList(),
    val spider: String = ""
)

/**
 * 订阅 JSON 中 sites 数组的单条站源声明。
 * TVBox 生态里字段经常缺失或类型混杂（type 可能是 "1" 字符串），
 * 实际解析走 TvBoxConfigParser 的容忍式手工提取，这里只保留 @Serializable 以便落盘/缓存。
 */
@Serializable
data class SiteConfig(
    val key: String = "",
    val name: String = "",
    /** 0=XML CMS, 1=JSON CMS(苹果CMS), 3=PY 源, 4=PHP 站（api 是 PHP 脚本的 HTTP 地址，走 Type4Api） */
    val type: Int = 1,
    val api: String = "",
    val searchable: Int = 1,
    val quickSearch: Int = 0,
    val filterable: Int = 0,
    val ext: String = "",
    val jar: String = "",
    /** 扩展字段"类型"：如 "小说"/"漫画"（type=4 站用它标识非视频站） */
    val typeLabel: String = "",
    /** 内部字段：聚合搜索拼装的配置 JSON 里带的订阅 URL（spider 站定位 jar 用） */
    val subUrl: String = ""
)

/**
 * 解析线路（parses 数组单项，v0.7 接入播放链路）。
 *
 * 真实订阅里 ext 可能是字符串，也可能是对象
 * `{"flag":[...],"header":{"User-Agent":"..."}}` —— TvBoxConfigParser.toParseConfig
 * 会把对象形态拆成 header/flags 字段，ext 只保留字符串形态。
 */
@Serializable
data class ParseConfig(
    val name: String = "",
    val url: String = "",
    /** 0=WebView 嗅探页（加载 url+视频地址抓 .m3u8/.mp4），1=JSON 接口（GET url+视频地址取 url 字段） */
    val type: Int = 0,
    val ext: String = "",
    /** ext 对象里的 header（如 User-Agent），请求解析线路时带上 */
    val header: Map<String, String> = emptyMap(),
    /** ext 对象里的 flag（该线路声明可解析的站点 flag），v0.7 仅解析保留、暂不参与过滤 */
    val flags: List<String> = emptyList()
)

/** 运行时站源：从 SiteConfig 规整化而来，Repository / UI 只认这个。 */
data class Site(
    val key: String,
    val name: String,
    val type: Int,
    val api: String,
    val searchable: Boolean = true,
    val quickSearch: Boolean = false,
    /** 扩展字段"类型"（如 "小说"/"漫画"），从 SiteConfig.typeLabel 规整化而来 */
    val typeLabel: String = "",
    /** spider 站的 ext 参数（传给 Spider.init 的 extend）；订阅站源的订阅 URL（定位 jar 包用） */
    val ext: String = "",
    val subUrl: String = ""
)

fun SiteConfig.toSite(): Site = Site(
    key = key,
    name = name.ifBlank { key },
    type = type,
    api = api.trim(),
    searchable = searchable != 0,
    quickSearch = quickSearch != 0,
    typeLabel = typeLabel.trim(),
    ext = ext.trim(),
    subUrl = subUrl
)

/**
 * 影片条目。open class 以便 VodDetail 继承全部字段
 * （Kotlin 不允许 data class 继承 data class）。
 */
@Serializable
open class Vod(
    open val vodId: String = "",
    open val vodName: String = "",
    open val vodPic: String = "",
    open val vodRemarks: String = "",
    open val vodYear: String = "",
    open val typeName: String = "",
    open val vodContent: String = ""
)

/** 影片详情：继承 Vod 全部字段，追加介绍/演员/导演/播放来源与播放地址。
 *  注意：不加 @Serializable（由 CmsApi 手工构造），避免与父类 Vod 的同名属性
 *  在 kotlinx.serialization 中产生重复 serial name 编译错误。 */
data class VodDetail(
    override val vodId: String = "",
    override val vodName: String = "",
    override val vodPic: String = "",
    override val vodRemarks: String = "",
    override val vodYear: String = "",
    override val typeName: String = "",
    val vodDesc: String = "",
    val vodActor: String = "",
    val vodDirector: String = "",
    /** 播放来源名，TVBox 约定以 $$$ 分隔，与 vodPlayUrl 的分组一一对应 */
    val vodPlayFrom: String = "",
    /** 播放地址原文：$$$ 切来源分组，# 切分集，$ 切"名称$地址" */
    val vodPlayUrl: String = ""
) : Vod(vodId, vodName, vodPic, vodRemarks, vodYear, typeName)

/** 单集：flag=来源线路名（如 m3u8 / 播放来源），name=集名，url=真实播放地址。 */
@Serializable
data class Episode(
    val flag: String = "",
    val name: String = "",
    val url: String = ""
)

/**
 * 播放线路：name=线路名（vod_play_from 的分组名，如"线路1"/"m3u8"），
 * episodes=该线路的选集。由 UI 层按 Episode.flag 分组得到，
 * VodDetail 本体仍只存 play_from / play_url 原文。
 */
data class PlayLine(
    val name: String,
    val episodes: List<Episode> = emptyList()
)

/** 分类：对应 CMS 的 class 数组项 {type_id, type_name}。 */
@Serializable
data class CategoryType(
    val typeId: String = "",
    val typeName: String = "",
    /** v0.30.1：PY 规则 homeContent 的 filters 字典按 tid 切出来的二级筛选组。 */
    val filters: List<FilterGroup> = emptyList()
)

// ---------------- 站源类型判定（Repository / UI 共用，v0.5.1 统一口径） ----------------

/** PhpSourceManager 合成站源：key 前缀 "php:"（v0.151.0 起走 PhpCliEngine 执行）。 */
fun Site.isPhpSite(): Boolean = key.startsWith("php:", ignoreCase = true)

/**
 * csp_ 开头的 Java spider 站：规则是跑在订阅自带 jar 里的 Java 类，
 * v0.8.0 起由 SpiderEngine 经 DexClassLoader 动态加载执行。
 */
fun Site.isJavaSpider(): Boolean = api.trim().startsWith("csp_", ignoreCase = true)

/** CMS 站：type=0（XML）/ type=1（JSON）。 */
fun Site.isCmsSite(): Boolean = type == 0 || type == 1

/**
 * PY 站：type=3 且 api 以 .py 结尾。
 * 注意旧逻辑曾把所有 type=3 都判成 PY（v0.5.1 修复：csp_ / drpy 不再误判）。
 */
fun Site.isPySite(): Boolean =
    !isJavaSpider() && type == 3 && api.trim().endsWith(".py", ignoreCase = true)

/** type=4 PHP 站：api 是 PHP 脚本的 HTTP 地址（PHP 跑在远端/本地服务里，壳子只发 HTTP 解析 JSON）。 */
fun Site.isType4Site(): Boolean = type == 4

/**
 * drpy 站：api 以 .js 结尾（含订阅下载到本地的规则）。
 * 注意 type==4 不再判 drpy（v0.6.0 起 type=4 = PHP 站，走 Type4Api）。
 */
fun Site.isDrpySite(): Boolean =
    !isJavaSpider() && !isType4Site() && api.trim().endsWith(".js", ignoreCase = true)

/**
 * 小说/漫画站：订阅声明的扩展字段"类型"含"小说"/"漫画"。
 * App 只做视频点播：home/search 跳过（返回空），站源列表打"不支持"徽标。
 */
fun Site.isNovelOrComic(): Boolean =
    typeLabel.trim().let { it.contains("小说") || it.contains("漫画") }

/** 订阅展开的子站点：key 前缀 "sub:"（见 SubSiteStore.toSite）。 */
fun Site.isSubSite(): Boolean = key.startsWith(SubSiteKeyPrefix)

const val SubSiteKeyPrefix = "sub:"
