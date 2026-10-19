package com.movieshell.data.source

import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import kotlinx.serialization.json.JsonObject

/**
 * CMS 风格 vod_* 字段 JSON → Vod / VodDetail（v0.112.0 A4 收敛）。
 *
 * CmsApi 与 Type4Api 的两份映射原来除 vodPic 处理外逐字一致；
 * 差异用 picTransform 参数承载：Type4 站 vod_pic 以 // 开头时补 https:，
 * 调用时传 picTransform = ::fixPic。PyApi 的映射有独有逻辑
 * （vod_id/id 回退、@@ 打包串拆标题图、vod_* 与 name/pic 等键名兼容、
 * 列表项填 vodContent），不强行合并，仍留在 PyApi 内部。
 */

/** CMS 风格列表项映射。 */
internal fun JsonObject.toVod(picTransform: (String) -> String = { it }): Vod = Vod(
    vodId = optString("vod_id"),
    vodName = optString("vod_name"),
    vodPic = picTransform(optString("vod_pic")),
    vodRemarks = optString("vod_remarks"),
    vodYear = optString("vod_year"),
    typeName = optString("type_name")
)

/** CMS 风格详情映射。 */
internal fun JsonObject.toVodDetail(picTransform: (String) -> String = { it }): VodDetail = VodDetail(
    vodId = optString("vod_id"),
    vodName = optString("vod_name"),
    vodPic = picTransform(optString("vod_pic")),
    vodRemarks = optString("vod_remarks"),
    vodYear = optString("vod_year"),
    typeName = optString("type_name"),
    vodDesc = optString("vod_content").stripHtml(),
    vodActor = optString("vod_actor"),
    vodDirector = optString("vod_director"),
    vodPlayFrom = optString("vod_play_from"),
    vodPlayUrl = optString("vod_play_url")
)

/** pic 以 // 开头补 https:（原 Type4Api 私有 fixPic，原样搬出）。 */
internal fun String.fixPic(): String = if (startsWith("//")) "https:$this" else this
