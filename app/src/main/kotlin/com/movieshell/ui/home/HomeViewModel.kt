package com.movieshell.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Vod as ModelVod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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

    /** 要点2：当前展示列表——派生状态，换分类只是换数据源 */
    val currentVideos: StateFlow<List<ModelVod>> = combine(
        recommendCache, _categoryCache, selectedCategoryId,
    ) { rec, catMap, catId ->
        if (catId == 0) rec else catMap[catId].orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** 切分类：只换 selectedCategoryId（写回 SavedStateHandle），currentVideos 自动派生，不碰列表组件 */
    fun selectCategory(catId: Int) {
        savedState["selectedCatId"] = catId
    }

    /**
     * 要点3的配套：detail 返回时调用。
     * VM 在 back stack 里没重建，currentVideos 第一帧就是缓存，不需要做任何事；
     * 只有缓存空（比如进程被杀后回来）才返回 true，调用方再触发轻量刷新。
     */
    fun needRefreshOnReturn(): Boolean = currentVideos.value.isEmpty()
}
