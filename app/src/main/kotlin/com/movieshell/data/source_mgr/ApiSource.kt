package com.movieshell.data.source_mgr

/** 接口站源（订阅）条目快照；v0.112.0 加 note/group（订阅级备注/分组），缺字段的老数据读作空串 */
data class ApiSource(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val note: String = "",
    val group: String = "",
    /** v0.122.0：多仓自动展开出的子仓记录其父仓 URL；空串=用户自加的顶层订阅 */
    val parentUrl: String = "",
)
