package com.movieshell.data.source

import android.util.Log
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.FilterOption
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.LogBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * PY 规则（hipy 风格）客户端：经 PyEngine 调规则文件的 Spider 方法，
 * 把返回的 dict 转成 App 的 Vod / VodDetail / CategoryType 模型。
 *
 * hipy 方法与返回约定（base.spider）：
 * - homeContent(filter)      -> {"class":[{type_id,type_name}], "filters":{..}}（部分规则带 list）
 * - homeVideoContent()       -> {"list":[...]}（推荐列表；黄果 v18 等规则走这里）
 * - categoryContent(tid,pg,filter,extend) -> {"page":n,"pagecount":n,"list":[...],"total":n}
 * - detailContent(ids)       -> {"list":[{vod_id,vod_name,vod_pic,vod_content,vod_actor,
 *                                vod_director,vod_play_from,vod_play_url,...}]}
 * - playerContent(flag,id,vipFlags) -> {"url":..,"parse":0/1,"header":{..}}
 * - searchContent(key,quick,pg) -> {"list":[...]}
 *
 * v0.27.1：部分规则把 vod_id 打包成 `tid@@标题@@图片@@作者`，解析时拆出标题/图片做回退；
 * playerContent 必须传 vipFlags（DsPlayer 三参数约定），否则规则端 TypeError。
 */
class PyApi(private val engine: PyEngine = ChaquopyEngine) {

    private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解开 {"ok":true,"data":{...}} 信封；失败返回 null。 */
    private fun envelope(text: String): JsonObject? = runCatching {
        val root = jsonLenient.parseToJsonElement(text).jsonObject
        val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull == "true"
        if (!ok) {
            val err = root["error"]?.toString() ?: "未知错误"
            Log.w(TAG, "PY 规则返回失败: $err")
            LogBus.e(TAG, "PY 规则失败: $err")
            // v0.27.4：失败时附带规则最近一次 HTTP 响应的元信息（URL/状态/编码/前200字节hex），供诊断
            root["last_resp"]?.let { LogBus.i(TAG, "PY 最近响应: $it") }
            // v0.27.2：缺库（No module named）挂起一键安装引导
            PyDepGuide.reportError(err)
            return null
        }
        (root["data"] as? JsonObject)
    }.getOrNull()

    private fun dataList(text: String): List<JsonObject> =
        envelope(text)?.let { (it["list"] as? JsonArray).orEmpty().mapNotNull { e -> e as? JsonObject } }
            .orEmpty()

    /**
     * v0.28.0：homeContent 响应短时共享。
     * 真因：HomeScreen 的推荐和分类原来各调一次 Python（home()/categories()），
     * 两次 homeContent 在 Chaquopy 里串行排队，真机上分类那次经常排过 12s 熔断，
     * 而规则日志里其实返回了 17 个分类（超时后才到）。
     * 现在 10s 内同规则共享一次调用结果，推荐/分类各取所需。
     */
    private var sharedHomePath: String? = null
    private var sharedHomeData: JsonObject? = null
    private var sharedHomeAt: Long = 0L

    // v0.50.0：homeContent 连续 3 次无推荐列表的规则，标记为"无首页推荐"。
    // 命中时跳过 list 解析直调 homeVideoContent（省一次串行等待；如实说明：
    // homeContent 那次 invoke 省不掉——categories() 吃的是同一次调用，10s 共享）。
    private val noRecommendRules = mutableSetOf<String>()
    private val noRecommendHits = mutableMapOf<String, Int>()

    private suspend fun sharedHomeContent(rulePath: String): JsonObject? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (rulePath == sharedHomePath && now - sharedHomeAt < 10_000) {
            return sharedHomeData
        }
        val data = envelope(engine.invoke(rulePath, "homeContent", """{"filter":true}"""))
        sharedHomePath = rulePath
        sharedHomeData = data
        sharedHomeAt = now
        return data
    }

    /**
     * 首页推荐。TVBox PY 有两种约定：
     * 1. homeContent 直接带 list；
     * 2. homeContent 只给 class，推荐列表在 homeVideoContent()（黄果 v18 等规则）。
     * 先 1 后 2，保证两类规则首页都有推荐。
     */
    suspend fun home(rulePath: String): List<Vod> {
        val data = sharedHomeContent(rulePath)
        val first = ((data?.get("list") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject) }).map { it.toVod() }
        if (first.isNotEmpty()) {
            noRecommendHits.remove(rulePath)
            return first
        }
        // v0.50.0：连续空标记
        val hits = (noRecommendHits[rulePath] ?: 0) + 1
        noRecommendHits[rulePath] = hits
        if (hits >= 3 && noRecommendRules.add(rulePath)) {
            LogBus.i(TAG, "规则连续 $hits 次 homeContent 无推荐，已标记直调 homeVideoContent")
        }
        if (rulePath in noRecommendRules) {
            LogBus.i(TAG, "规则已标记无首页推荐，直调 homeVideoContent")
        } else {
            LogBus.i(TAG, "homeContent 无推荐列表，改调 homeVideoContent")
        }
        return dataList(engine.invoke(rulePath, "homeVideoContent", "{}"))
            .map { it.toVod() }
    }

    /**
     * 分类列表（homeContent 的 class 数组）。
     * v0.27.7：打日志——规则返回 class 数量，空时 warn（真机分类反复拿不到，先拿数据）。
     * v0.30.1：同时解析顶层 filters 字典，按 tid 切出每个分类的二级筛选组。
     */
    suspend fun categories(rulePath: String): List<CategoryType> = runCatching {
        val data = sharedHomeContent(rulePath)
            ?: return emptyList()
        // filters: {tid: [{key,name,value:[{n,v}]}]}
        val filtersMap = (data["filters"] as? JsonObject)?.entries.orEmpty()
            .associate { (tid, v) ->
                tid to (v as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .map { g ->
                        FilterGroup(
                            key = g.optString("key"),
                            name = g.optString("name"),
                            options = (g["value"] as? JsonArray).orEmpty()
                                .mapNotNull { it as? JsonObject }
                                .map { FilterOption(it.optString("n"), it.optString("v")) }
                                .filter { it.name.isNotBlank() },
                        )
                    }.filter { it.options.isNotEmpty() }
            }
        val list = (data["class"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject) }.map {
                val tid = it.optString("type_id")
                CategoryType(
                    typeId = tid,
                    typeName = it.optString("type_name"),
                    filters = filtersMap[tid].orEmpty(),
                )
            }.filter { it.typeId.isNotBlank() }
        if (list.isEmpty()) LogBus.w(TAG, "规则 categories 返回空（homeContent 无 class 数组）")
        else LogBus.i(TAG, "规则 categories 返回 ${list.size} 个分类")
        list
    }.getOrDefault(emptyList())

    /** 分类翻页。extend 透传给规则（如 {"cat": 子分类 id}）。 */
    suspend fun category(
        rulePath: String,
        tid: String,
        pg: Int,
        extend: Map<String, String> = emptyMap()
    ): List<Vod> {
        val extendJson = extend.entries.joinToString(",", "{", "}") { (k, v) -> "${k.q()}:${v.q()}" }
        return dataList(
            engine.invoke(
                rulePath, "categoryContent",
                """{"tid":${tid.q()},"pg":$pg,"filter":true,"extend":$extendJson}"""
            )
        ).map { it.toVod() }
    }

    /** 详情。 */
    suspend fun detail(rulePath: String, vodId: String): VodDetail = runCatching {
        val first = dataList(
            engine.invoke(rulePath, "detailContent", """{"ids":[${vodId.q()}]}""")
        ).firstOrNull() ?: return VodDetail(vodId = vodId)
        first.toVodDetail()
    }.getOrDefault(VodDetail(vodId = vodId))

    /** 搜索。 */
    suspend fun search(rulePath: String, wd: String): List<Vod> =
        dataList(
            engine.invoke(
                rulePath, "searchContent",
                """{"key":${wd.q()},"quick":"false"}"""
            )
        ).map { it.toVod() }

    /**
     * 播放：返回 Triple(直链 url, parse 标记, 请求头)。
     * - vipFlags 必须传：DsPlayer 约定 playerContent(flag, id, vipFlags) 三参数，
     *   少传 vipFlags 会让部分规则（黄果 v18）直接 TypeError。
     * - header（如 Referer/UA）透传给播放器，防盗链站点必需。
     * - parse=1 表示需走解析线路，由调用方（PlayResolver）分流。
     */
    suspend fun play(rulePath: String, flag: String, id: String): Triple<String, Int, Map<String, String>> =
        runCatching {
            val data = envelope(
                engine.invoke(
                    rulePath, "playerContent",
                    """{"flag":${flag.q()},"id":${id.q()},"vipFlags":[]}"""
                )
            ) ?: return Triple("", 0, emptyMap())
            val url = data.optString("url")
            val parse = data["parse"]?.toString()?.trim('"')?.toIntOrNull() ?: 0
            val header = (data["header"] as? JsonObject)
                ?.entries?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                }?.toMap().orEmpty()
            if (url.isBlank()) LogBus.w(TAG, "playerContent 返回空 url（parse=$parse）")
            Triple(url, parse, header)
        }.getOrDefault(Triple("", 0, emptyMap()))

    // ---------------- 内部 ----------------

    private fun String.q(): String =
        "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun JsonObject.toVod(): Vod {
        val rawId = optString("vod_id").ifBlank { optString("id") }
        val packed = rawId.split("@@")
        return Vod(
            vodId = rawId,
            // v0.27.1：键名兼容 id/name/pic；`tid@@标题@@图片@@作者` 打包串拆出标题/图片做回退
            vodName = optString("vod_name").ifBlank { optString("name") }
                .ifBlank { packed.getOrNull(1).orEmpty() },
            vodPic = optString("vod_pic").ifBlank { optString("pic") }
                .ifBlank { packed.getOrNull(2).orEmpty() },
            vodRemarks = optString("vod_remarks").ifBlank { optString("remarks") },
            vodYear = optString("vod_year").ifBlank { optString("year") },
            typeName = optString("type_name"),
            vodContent = optString("vod_content").ifBlank { optString("vod_blurb") }
                .ifBlank { optString("desc") }
        )
    }

    private fun JsonObject.toVodDetail(): VodDetail {
        val rawId = optString("vod_id")
        val packed = rawId.split("@@")
        return VodDetail(
            vodId = rawId,
            vodName = optString("vod_name").ifBlank { packed.getOrNull(1).orEmpty() },
            vodPic = optString("vod_pic").ifBlank { packed.getOrNull(2).orEmpty() },
            vodRemarks = optString("vod_remarks"),
            vodYear = optString("vod_year"),
            typeName = optString("type_name"),
            vodDesc = optString("vod_content").stripHtml(),
            vodActor = optString("vod_actor"),
            vodDirector = optString("vod_director"),
            vodPlayFrom = optString("vod_play_from"),
            vodPlayUrl = optString("vod_play_url")
        )
    }

    private fun JsonObject.optString(key: String): String {
        val prim = (this[key] as? JsonPrimitive) ?: return ""
        if (prim is JsonNull) return ""
        return prim.contentOrNull.orEmpty()
    }

    private fun String.stripHtml(): String =
        this.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()

    companion object {
        private const val TAG = "PyApi"
    }
}
