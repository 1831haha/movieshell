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
     * 要点1：当前选中分类，""=推荐流；SavedStateHandle 存，进程重建也不丢。
     * getStateFlow 返回只读视图，写操作走 selectCategory()（同时写回 SavedStateHandle）。
     * v0.217.0（F1）：这里存的是分类的 typeId（CategoryType.typeId 本就是 String），
     * 不再是“第几个”（序号一变缓存就张冠李戴）；序号只做界面展示用，展示时按
     * typeId 到分类列表里找位置。key 用 selectedCatTypeId：老 key 存的是 Int，
     * 换类型读会崩，换 key 隔离。
     */
    val selectedCategoryId: StateFlow<String> =
        savedState.getStateFlow("selectedCatTypeId", "")

    /** 分类列表（37 个分类那种） */
    private val _categories = MutableStateFlow<List<CategoryType>>(emptyList())
    val categories: StateFlow<List<CategoryType>> = _categories

    /** 要点1：推荐流缓存（首页默认内容） */
    private val _recommendCache = MutableStateFlow<List<ModelVod>>(emptyList())
    val recommendCache: StateFlow<List<ModelVod>> = _recommendCache

    /** 要点1：分类内容缓存：typeId -> videos（内存级，返回秒回，与磁盘缓存同口径） */
    private val _categoryCache = MutableStateFlow<Map<String, List<ModelVod>>>(emptyMap())
    val categoryCache: StateFlow<Map<String, List<ModelVod>>> = _categoryCache

    /**
     * v0.217.0（F4）：分类分页状态搬进 VM，与分类缓存同生同灭——
     * 切 tab 回来界面重建也不丢页码，页码与“已合并几页”不再脱节。
     * key 均为 typeId；换源清缓存时一并清掉。
     */
    private val _categoryPages = MutableStateFlow<Map<String, Int>>(emptyMap())
    fun categoryPage(typeId: String): Int = _categoryPages.value[typeId] ?: 1
    fun setCategoryPage(typeId: String, page: Int) {
        _categoryPages.value = _categoryPages.value + (typeId to page)
    }
    private val _categoryNoMore = MutableStateFlow<Set<String>>(emptySet())
    val categoryNoMore: StateFlow<Set<String>> = _categoryNoMore
    fun isCategoryNoMore(typeId: String): Boolean = typeId in _categoryNoMore.value
    fun setCategoryNoMore(typeId: String, noMore: Boolean) {
        _categoryNoMore.value = if (noMore) {
            _categoryNoMore.value + typeId
        } else {
            _categoryNoMore.value - typeId
        }
    }

    /**
     * v0.58（用户指正：切分类闪全屏空白）：
     * 正在加载的分类 typeId，null=空闲。切到无缓存分类时不清空旧列表，
     * UI 层据此显示顶部细条 loading（有旧数据）或骨架屏（无旧数据）。
     */
    private val _loadingCategoryId = MutableStateFlow<String?>(null)
    val loadingCategoryId: StateFlow<String?> = _loadingCategoryId

    /**
     * v0.110.0：分类内容加载失败的分类 typeId（超时/规则异常），null=无失败。
     * 失败后 loadingCategoryId 同步清掉（不再转圈），UI 据此给内联错误+重试；
     * 之前失败只写空缓存，顶部细条转圈卡死、用户无任何反馈（审查报告第 10 条）。
     */
    private val _failedCategoryId = MutableStateFlow<String?>(null)
    val failedCategoryId: StateFlow<String?> = _failedCategoryId

    /** 标记某分类内容加载失败：停掉它的转圈，UI 切失败态 */
    fun markCategoryFailed(catId: String) {
        _failedCategoryId.value = catId
        if (_loadingCategoryId.value == catId) _loadingCategoryId.value = null
    }

    /** 发起新一轮分类加载时，清掉该分类的旧失败标记 */
    fun clearCategoryFailed(catId: String) {
        if (_failedCategoryId.value == catId) _failedCategoryId.value = null
    }

    /** 清掉某分类的加载标记（仅当标记正指着它时，防清掉新一轮的标记） */
    fun clearLoadingCategory(catId: String) {
        if (_loadingCategoryId.value == catId) _loadingCategoryId.value = null
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
                selectedCategoryId, _recommendCache, _categoryCache,
            ) { catId, rec, catMap ->
                Triple(catId, rec, catMap)
            }.collect { (catId, rec, catMap) ->
                // v0.212.0 审计修复：原来“缓存里有空列表”和“根本没缓存”
                // 分不清——规则真返回空时，空列表成功不走失败分支，收集器
                // 见目标为空又把 loading 置回，整屏骨架屏一直盖着、无空态。
                // 改按“有没有缓存条目”区分：有条目（哪怕是空列表）就按
                // 该条目展示并停转圈；没条目才标记加载中、保留旧列表。
                if (catId == "") {
                    if (rec.isNotEmpty()) {
                        _displayVideos.value = rec
                        if (_failedCategoryId.value == "") _failedCategoryId.value = null
                    }
                    // catId=="" 且推荐为空：首页加载中，保持旧展示不动
                } else if (catMap.containsKey(catId)) {
                    _displayVideos.value = catMap[catId].orEmpty()
                    if (_loadingCategoryId.value == catId) _loadingCategoryId.value = null
                    // v0.110.0：有缓存结果了，失败标记同步清除
                    if (_failedCategoryId.value == catId) _failedCategoryId.value = null
                } else if (_failedCategoryId.value != catId) {
                    // 无缓存：保留旧列表，标记正在加载（UI 显示顶部细条/骨架屏）；
                    // v0.110.0：已标记失败的不再转圈，失败态走 failedCategoryId（UI 给内联重试）
                    _loadingCategoryId.value = catId
                }
            }
        }
    }

    /** 是否正在加载（局部转圈用） */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** 写加载态（原来界面层各处直写 vm.isLoading.value） */
    fun setLoading(v: Boolean) {
        _isLoading.value = v
    }

    /**
     * v0.218.0（S1）：换源清空单一入口——原来调用方要按顺序调齐
     * setRecommend／setCategories／clearCategoryCache／clearDisplay／
     * selectCategory，漏一个就残留旧源内容；现在 VM 内一次清完。
     * （筛选 Map 是界面层本地态，调用方仍需自己 setFilters(emptyMap())。）
     */
    fun clearForSourceSwitch() {
        setRecommend(emptyList())
        setCategories(emptyList())
        clearCategoryCache()
        clearDisplay()
        selectCategory("")
    }

    /** 写推荐缓存 */
    fun setRecommend(videos: List<ModelVod>) {
        _recommendCache.value = videos
    }

    /** 写某分类缓存（key 为 typeId，与磁盘缓存同口径） */
    fun setCategoryVideos(typeId: String, videos: List<ModelVod>) {
        _categoryCache.value = _categoryCache.value + (typeId to videos)
    }

    /**
     * 写分类列表（F1）：刷新替换分类列表时按 typeId 校验——
     * 缓存里不在新列表里的 typeId 一律清掉，选中／加载／失败标记若指向
     * 已不存在的 typeId 也复位，不让旧序号／旧标记贴到新分类上。
     * （SavedStateHandle 里存的老序号也会在这里被纠回推荐态。）
     */
    fun setCategories(cats: List<CategoryType>) {
        _categories.value = cats
        val typeIds = cats.map { it.typeId }.toSet()
        _categoryCache.value = _categoryCache.value.filterKeys { it in typeIds }
        _categoryPages.value = _categoryPages.value.filterKeys { it in typeIds }
        _categoryNoMore.value = _categoryNoMore.value.intersect(typeIds)
        if (selectedCategoryId.value != "" && selectedCategoryId.value !in typeIds) {
            selectCategory("")
        }
        if (_loadingCategoryId.value != null && _loadingCategoryId.value !in typeIds) {
            _loadingCategoryId.value = null
        }
        if (_failedCategoryId.value != null && _failedCategoryId.value !in typeIds) {
            _failedCategoryId.value = null
        }
    }

    /** 清空分类内存缓存（换源时调用，旧源缓存不能留；分页状态一并清） */
    fun clearCategoryCache() {
        _categoryCache.value = emptyMap()
        _categoryPages.value = emptyMap()
        _categoryNoMore.value = emptySet()
    }

    /**
     * v0.58：换源时清空展示（旧源的列表不能留）。
     * 调用方在 selectCategory(0) 之前调，collect 看到空数据不会自动清，需手动清。
     */
    fun clearDisplay() {
        _displayVideos.value = emptyList()
        _loadingCategoryId.value = null
        _failedCategoryId.value = null
    }

    /** 切分类：只换 selectedCategoryId（typeId，写回 SavedStateHandle），展示层按缓存情况决定切不切 */
    fun selectCategory(catId: String) {
        savedState["selectedCatTypeId"] = catId
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
