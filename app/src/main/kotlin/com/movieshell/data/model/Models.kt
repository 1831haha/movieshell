package com.movieshell.data.model

import kotlinx.serialization.Serializable

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
    /** 0=XML CMS, 1=JSON CMS(苹果CMS), 3=PY 源, 4=JS/DRPY 规则, 其他按扩展处理 */
    val type: Int = 1,
    val api: String = "",
    val searchable: Int = 1,
    val quickSearch: Int = 0,
    val filterable: Int = 0,
    val ext: String = "",
    val jar: String = ""
)

/** 解析线路（parses 数组单项），v0.1 仅解析保留，暂未接入播放链路。 */
@Serializable
data class ParseConfig(
    val name: String = "",
    val url: String = "",
    val type: Int = 0,
    val ext: String = ""
)

/** 运行时站源：从 SiteConfig 规整化而来，Repository / UI 只认这个。 */
data class Site(
    val key: String,
    val name: String,
    val type: Int,
    val api: String,
    val searchable: Boolean = true,
    val quickSearch: Boolean = false
)

fun SiteConfig.toSite(): Site = Site(
    key = key,
    name = name.ifBlank { key },
    type = type,
    api = api.trim(),
    searchable = searchable != 0,
    quickSearch = quickSearch != 0
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
    open val typeName: String = ""
)

/** 影片详情：继承 Vod 全部字段，追加介绍/演员/导演/播放来源与播放地址。 */
@Serializable
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

/** 分类：对应 CMS 的 class 数组项 {type_id, type_name}。 */
@Serializable
data class CategoryType(
    val typeId: String = "",
    val typeName: String = ""
)
