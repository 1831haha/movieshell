package com.movieshell.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Vod as ModelVod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/**
 * v0.53.0：首页状态容器——三点核心。
 *
 * 1. selectedCategoryId 和分类缓存都在 VM 里：选中经 SavedStateHandle 持久化，
 *    返回重建不丢；分类内容缓存在内存 Map 里，返回秒回；
 * 2. currentVideos 是派生 StateFlow：选分类只是换数据源（recommendCache /
 *    categoryCache[id]），列表组件不重建、不切页面；
 * 3. MainActivity 里 composable("home") 用 viewModel() 取实例——NavHost 在
 *    navigate("detail") 时把 home 的 back stack entry 留着，VM 不重建；
 *    popBackStack 回来时第一帧直接出缓存好的分类列表，不闪空。
 *
 * 注意：复杂加载逻辑（HomeLoadGate 单飞、退避、过滤器等）仍在 HomeScreen 里，
 * 这里只管状态容器，逻辑写 VM 的状态（不直接碰 Composable 的 remember）。
 */
class HomeViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * 要点1：当前选中分类，0=推荐流；SavedStateHandle 存，进程重建也不丢。
     * getStateFlow 返回只读视图，写操作走 selectCategory()（同时写回 SavedStateHandle）。
     */
    val selectedCategoryId: StateFlow<Int> =
        savedState.getStateFlow("selectedCatId", 0)

    /** 分类列表（37 个分类那种） */
    val categories = MutableStateFlow<List<CategoryType>>(emptyList())

    /** 要点1：推荐流缓存（首页默认内容） */
    val recommendCache = MutableStateFlow<List<ModelVod>>(emptyList())

    /** 要点1：分类内容缓存：categoryId -> videos（内存级，返回秒回） */
    private val _categoryCache = MutableStateFlow<Map<Int, List<ModelVod>>>(emptyMap())
    val categoryCache: StateFlow<Map<Int, List<ModelVod>>> = _categoryCache

    /**
     * v0.58（用户指正：切分类闪全屏空白）：
     * 正在加载的分类 id，null=空闲。切到无缓存分类时不清空旧列表，
     * UI 层据此显示顶部细条 loading（有旧数据）或骨架屏（无旧数据）。
     */
    val loadingCategoryId = MutableStateFlow<Int?>(null)

    /**
     * v0.110.0：分类内容加载失败的分类 id（超时/规则异常），null=无失败。
     * 失败后 loadingCategoryId 同步清掉（不再转圈），UI 据此给内联错误+重试；
     * 之前失败只写空缓存，顶部细条转圈卡死、用户无任何反馈（审查报告第 10 条）。
     */
    val failedCategoryId = MutableStateFlow<Int?>(null)

    /** 标记某分类内容加载失败：停掉它的转圈，UI 切失败态 */
    fun markCategoryFailed(catId: Int) {
        failedCategoryId.value = catId
        if (loadingCategoryId.value == catId) loadingCategoryId.value = null
    }

    /** 发起新一轮分类加载时，清掉该分类的旧失败标记 */
    fun clearCategoryFailed(catId: Int) {
        if (failedCategoryId.value == catId) failedCategoryId.value = null
    }

    /**
     * 要点2改：当前展示列表——不再是纯派生。切分类时：
     * - 有缓存：立刻切到缓存；
     * - 无缓存：保留旧列表（不闪空），等网络回来再替换。
     */
    private val _displayVideos = MutableStateFlow<List<ModelVod>>(emptyList())
    val currentVideos: StateFlow<List<ModelVod>> = _displayVideos

    init {
        // 选中的分类/缓存变化时，决定展示什么（只在有数据时更新，不闪空）
        viewModelScope.launch {
            combine(
                selectedCategoryId, recommendCache, _categoryCache,
            ) { catId, rec, catMap ->
                Triple(catId, rec, catMap)
            }.collect { (catId, rec, catMap) ->
                val target: List<ModelVod>? = if (catId == 0) rec else catMap[catId]
                if (!target.isNullOrEmpty()) {
                    _displayVideos.value = target
                    if (loadingCategoryId.value == catId) loadingCategoryId.value = null
                    // v0.110.0：有数据了，失败标记同步清除
                    if (failedCategoryId.value == catId) failedCategoryId.value = null
                } else if (catId != 0 && failedCategoryId.value != catId) {
                    // 无缓存：保留旧列表，标记正在加载（UI 显示顶部细条/骨架屏）；
                    // v0.110.0：已标记失败的不再转圈，失败态走 failedCategoryId（UI 给内联重试）
                    loadingCategoryId.value = catId
                }
                // catId==0 且推荐为空：首页加载中，保持旧展示不动
            }
        }
    }

    /** 是否正在加载（局部转圈用） */
    val isLoading = MutableStateFlow(false)

    /** 写推荐缓存 */
    fun setRecommend(videos: List<ModelVod>) {
        recommendCache.value = videos
    }

    /** 写某分类缓存 */
    fun setCategoryVideos(catId: Int, videos: List<ModelVod>) {
        _categoryCache.value = _categoryCache.value + (catId to videos)
    }

    /** 写分类列表 */
    fun setCategories(cats: List<CategoryType>) {
        categories.value = cats
    }

    /** 读某分类的内存缓存（没有返回 null） */
    fun getCategoryCache(catId: Int): List<ModelVod>? = _categoryCache.value[catId]

    /** 清空分类内存缓存（换源时调用，旧源缓存不能留） */
    fun clearCategoryCache() {
        _categoryCache.value = emptyMap()
    }

    /**
     * v0.58：换源时清空展示（旧源的列表不能留）。
     * 调用方在 selectCategory(0) 之前调，collect 看到空数据不会自动清，需手动清。
     */
    fun clearDisplay() {
        _displayVideos.value = emptyList()
        loadingCategoryId.value = null
        failedCategoryId.value = null
    }

    /** 切分类：只换 selectedCategoryId（写回 SavedStateHandle），展示层按缓存情况决定切不切 */
    fun selectCategory(catId: Int) {
        savedState["selectedCatId"] = catId
        // v0.58：切到无缓存分类时，init 里的 collect 会置 loadingCategoryId 并保留旧列表；
        // 网络回来调 setCategoryVideos，collect 自动刷新展示并清 loading
    }

    /**
     * 要点3的配套：detail 返回时调用。
     * VM 在 back stack 里没重建，currentVideos 第一帧就是缓存，不需要做任何事；
     * 只有缓存空（比如进程被杀后回来）才返回 true，调用方再触发轻量刷新。
     */
    fun needRefreshOnReturn(): Boolean = currentVideos.value.isEmpty()
}
