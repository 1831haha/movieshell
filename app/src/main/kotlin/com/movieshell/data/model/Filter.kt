package com.movieshell.data.model

/**
 * drpy 分类筛选（filterable 规则）的数据模型。
 *
 * 对应规则里的 filter 定义：
 * filter: { "<tid>": [ { key, name, value: [ { n, v } ] } ] }
 * 渲染时 filter_url 里的 {{fl.键名}} 占位符取对应分组选中项的 v。
 */
data class FilterOption(
    /** 显示名（规则里 value 项的 n，如"全部"/"2025"）。 */
    val name: String,
    /** 实际值（规则里 value 项的 v，拼进 URL 的）。 */
    val value: String
)

data class FilterGroup(
    /** 占位符键名（{{fl.key}} 的 key，如 "year"）。 */
    val key: String,
    /** 分组显示名（规则的 name，如"年份："）。 */
    val name: String,
    val options: List<FilterOption>,
    /**
     * 默认值：首个选项的 value（规则里首项一般是"全部"）。
     * 无筛选值时引擎按此渲染（drpy2 行为：filter_def[tid] 优先，其次首项）。
     */
    val defaultValue: String = options.firstOrNull()?.value.orEmpty()
)
