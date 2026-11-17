package com.movieshell.ui.novel

import android.app.Application
import android.content.Context
import com.movieshell.ui.components.AppToast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movieshell.domain.model.Novel
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.novel.NovelDirectoryFill
import com.movieshell.data.store.NovelStore
import com.movieshell.data.store.SearchHistoryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.103.0：找书页状态容器（搜索页切后台中断修复）。
 *
 * 根因同 MusicSearchViewModel：原 NovelSearchScreen 用 rememberCoroutineScope + remember，
 * 切到设置页后 scope 被取消，在飞的多书源并发搜索中断、结果丢失。
 * 改：activity 级 VM（viewModelScope），切页/切 tab 不销毁，搜索不中断、结果保留。
 * Toast/context 统一用 applicationContext。
 *
 * v0.66.0 锁单源：lockPyName 由页面经 setLockPyName 传入（LaunchedEffect(pySourceName) 同步）；
 * 锁变化时清空旧结果（等价于原来"新路由=新 composition=空结果"）。
 */
class NovelSearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _results = MutableStateFlow<List<SourceGroup>>(emptyList())
    val results: StateFlow<List<SourceGroup>> = _results

    private val _searched = MutableStateFlow(false)
    val searched: StateFlow<Boolean> = _searched

    /**
     * v1.1.12：本次搜书的失败汇总（影视侧 SearchViewModel.failedLabel 同款口径），
     * null=本次无书源失败。失败时旧结果保留、顶行明说；只有无失败且 0 条时才算“没找到”。
     */
    private val _failedLabel = MutableStateFlow<String?>(null)
    val failedLabel: StateFlow<String?> = _failedLabel

    /**
     * v1.2.24：精准搜索（阅读 precisionSearch 同款，默认关）——只留书名/作者
     * 含关键词的书，杂结果直接滤掉。开关持久化，切换后按同一词重搜一次。
     */
    private val searchPrefs by lazy {
        getApplication<Application>().getSharedPreferences("novel_search_prefs", Context.MODE_PRIVATE)
    }
    private val _precisionSearch = MutableStateFlow(false)
    val precisionSearch: StateFlow<Boolean> = _precisionSearch

    /** v1.2.24：本轮精准过滤掉的总条数（>0 时界面顶行提示）。 */
    private val _filteredCount = MutableStateFlow(0)
    val filteredCount: StateFlow<Int> = _filteredCount

    /** v1.2.24：单源加载更多——正在加载下一页的源 key。 */
    private val _loadingMore = MutableStateFlow<Set<String>>(emptySet())
    val loadingMore: StateFlow<Set<String>> = _loadingMore

    /** v1.2.24：单源加载更多——已翻到底（某页 0 条新书）的源 key，不再显示加载更多。 */
    private val _exhaustedGroups = MutableStateFlow<Set<String>>(emptySet())
    val exhaustedGroups: StateFlow<Set<String>> = _exhaustedGroups

    /** v1.2.24：本轮搜索的关键词（加载更多沿用它，不读可能已被用户改过的输入框）。 */
    private var searchedKeyword: String = ""

    /** v1.2.24：各源下一页页码（新一轮搜索时清空）。 */
    private val nextPageBySource = mutableMapOf<String, Int>()

    // v0.220.0：忙状态改集合——连点多本时每本各管各的“加入中”，原来单槽位
    // 会让先点的那本弹回“加入书架”。
    private val _joining = MutableStateFlow<Set<String>>(emptySet())
    val joining: StateFlow<Set<String>> = _joining

    /** v0.67.0：小说搜索历史（独立作用域） */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history

    /** v0.66.0：锁定的 PY 源名（书源管理点源直达时带入） */
    var lockPyName: String? = null
        private set

    /** v0.109.0 A4：连续搜索时取消前一次的搜索协程 */
    private var searchJob: Job? = null

    /** v0.217.0（F39）：书源载入句柄（init 里启动，doSearch 等它）。 */
    private var loadJob: Job? = null

    /**
     * v0.247.0：书源载入是否失败——载入失败时内存表是空的，不能误判成
     * “没有启用的书源”。doSearch 在无可用源时据此换提示；载入抛异常也算失败。
     */
    private var loadFailed = false

    init {
        _precisionSearch.value = searchPrefs.getBoolean("precision_search", false)
        // v0.217.0（F39）：载入句柄留下来给 doSearch 等——原来 fire-and-forget，
        // 用户一进页就搜时读到的是空表，误报“没有启用的书源”。
        loadJob = viewModelScope.launch {
            // v0.247.0 第 2 轮：两类分开尝试——JSON 抛异常不再跳过 PY 载入；
            // 取消仍原样抛出（两处 onFailure 的 throw），不被当成载入失败。
            val okJson = runCatching { BookSourceManager.load(getApplication()) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(false)
            val okPy = runCatching { PyBookSourceManager.load(getApplication()) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(false)
            loadFailed = !(okJson && okPy)
        }
        refreshHistory()
    }

    fun setLockPyName(name: String?) {
        if (lockPyName != name) {
            lockPyName = name
            _results.value = emptyList()
            _searched.value = false
            _failedLabel.value = null
            _keyword.value = ""
        }
    }

    /** v1.2.24：精准开关——写盘持久化；已搜过时按同一词重搜一次。 */
    fun setPrecision(value: Boolean) {
        if (_precisionSearch.value == value) return
        _precisionSearch.value = value
        searchPrefs.edit().putBoolean("precision_search", value).apply()
        if (_searched.value) doSearch()
    }

    /** v1.2.24：手动停止搜索（阅读 FAB 停止同款）——已出的结果留着，不清空。 */
    fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        _loading.value = false
    }

    /**
     * v1.2.24：单源加载更多（阅读触底续搜的单源版）——取该源下一页追加到组尾，
     * 按 remoteId 去重；某页 0 条新书即判该源已到底，不再显示加载更多。
     * PY 源无分页（searchAllPy 只抓一页），不处理。
     */
    fun loadMore(groupKey: String) {
        if (groupKey.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)) return
        if (groupKey in _loadingMore.value || groupKey in _exhaustedGroups.value) return
        val kw = searchedKeyword
        if (kw.isEmpty()) return
        val source = BookSourceManager.sources.value.find { it.key == groupKey } ?: return
        val page = nextPageBySource[groupKey] ?: 2
        viewModelScope.launch {
            _loadingMore.value = _loadingMore.value + groupKey
            try {
                val books = NovelSourceEngine.search(source, kw, allowGate = false, pageNum = page)
                val (kept, dropped) = NovelSourceEngine.applyPrecisionFilter(
                    books, kw, _precisionSearch.value,
                )
                _filteredCount.value += dropped
                val group = _results.value.find { it.key == groupKey } ?: return@launch
                val seen = group.books.map { it.remoteId }.toHashSet()
                val newBooks = kept.filter { seen.add(it.remoteId) }
                if (newBooks.isEmpty()) {
                    _exhaustedGroups.value = _exhaustedGroups.value + groupKey
                } else {
                    _results.value = _results.value.map { g ->
                        if (g.key == groupKey) g.copy(books = g.books + newBooks) else g
                    }
                    nextPageBySource[groupKey] = page + 1
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(getApplication(), "加载更多失败", long = false)
            } finally {
                _loadingMore.value = _loadingMore.value - groupKey
            }
        }
    }

    /** v1.2.24：该组是否可加载更多（PY 源无分页、已到底的不显示）。 */
    fun canLoadMore(groupKey: String): Boolean =
        !groupKey.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX) &&
            groupKey !in _exhaustedGroups.value

    fun setKeyword(k: String) {
        _keyword.value = k
    }

    // v0.231.0：重进找书不留上次记录——清结果＋清已搜标记，在途搜索一并取消，免得旧结果晚一步又填回来。
    fun clearResults() {
        searchJob?.cancel()
        searchJob = null
        _results.value = emptyList()
        _searched.value = false
        _failedLabel.value = null
    }

    /** v0.294.55：离开找书（取消/返回/切走 tab）时整体复位——词、结果、已搜标记一起清，
     * 重进找书不会再看到上一次（甚至更早一次）的结果。 */
    fun resetSearch() {
        clearResults()
        _keyword.value = ""
    }

    fun refreshHistory() {
        // v0.270.0（T27）：同影视搜索，历史读盘走 IO。
        viewModelScope.launch {
            _history.value = withContext(Dispatchers.IO) { SearchHistoryStore.list(getApplication(), "novel") }
        }
    }

    fun clearHistory() {
        SearchHistoryStore.clear(getApplication(), "novel")
        refreshHistory()
    }

    fun removeHistory(h: String) {
        SearchHistoryStore.remove(getApplication(), h, "novel")
        refreshHistory()
    }

    private fun lockedPyRule() =
        lockPyName?.let { name -> PyBookSourceManager.rules.value.find { it.name == name } }

    fun doSearch() {
        // v0.217.0（F39）：书源载入未完成时先等载入、再按原逻辑跑一次——
        // 不在空表上提前判“没有启用的书源”。载入异常也继续（join 异常已吞），
        // 此时空表才是真的无源，提示才可信。
        val pendingLoad = loadJob?.takeIf { !it.isCompleted }
        if (pendingLoad != null) {
            viewModelScope.launch {
                runCatching { pendingLoad.join() }
                    .onFailure { if (it is CancellationException) throw it }
                doSearch()
            }
            return
        }
        val kw = _keyword.value.trim()
        val locked = lockedPyRule()
        // v0.66.0：锁定模式下只要该源存在即可搜
        val canSearch = if (locked != null) {
            true
        } else {
            BookSourceManager.sources.value.any { it.enabled } ||
                PyBookSourceManager.rules.value.any { it.name in PyBookSourceManager.enabled.value }
        }
        if (kw.isEmpty() || !canSearch) {
            if (!canSearch) {
                // v0.247.0：载入失败的空表不再报“没有启用的书源”——
                // 用户真有源，只是文件读坏了，提示要指向书源管理。
                AppToast.show(
                    getApplication(),
                    if (loadFailed) "书源加载失败，文件可能已损坏，去书源管理查看" else "没有启用的书源，先去书源管理添加",
                    long = false,
                )
            }
            return
        }
        // v0.67.0：记搜索历史
        SearchHistoryStore.add(getApplication(), kw, "novel")
        refreshHistory()
        // v0.109.0 A4：连续搜索取消前一次——旧慢请求后返回覆盖新结果；try/finally
        // 保证被取消时 loading 也能落下
        searchJob?.cancel()
        // v1.1.12：新搜索开始不再先清旧结果——失败时旧结果要留着、顶行明说“显示的是
        // 上次的结果”；成功的新结果回来自然覆盖。失败汇总也在此清掉，旧汇总不串到新一轮。
        _failedLabel.value = null
        NovelSourceEngine.clearSearchFailures()
        // v1.2.24：新一轮清掉过滤计数与加载更多状态（页码/到底标记按源记）。
        _filteredCount.value = 0
        _loadingMore.value = emptySet()
        _exhaustedGroups.value = emptySet()
        nextPageBySource.clear()
        searchedKeyword = kw
        searchJob = viewModelScope.launch {
            val myJob = coroutineContext[Job]
            try {
                _loading.value = true
                _searched.value = true
                // v0.66.0：锁定单 PY 源 → 只搜它
                if (locked != null) {
                    _results.value = NovelSourceEngine.searchAllPy(getApplication(), listOf(locked), kw)
                        .filter { it.second.isNotEmpty() }
                        .map { (r, books) ->
                            SourceGroup(
                                PyBookSourceManager.SOURCE_KEY_PREFIX + r.name,
                                "${r.title}（本地PY）",
                                books,
                            )
                        }
                } else {
                    val enabledSources = BookSourceManager.sources.value.filter { it.enabled }
                    val enabledPyRules = PyBookSourceManager.rules.value
                        .filter { it.name in PyBookSourceManager.enabled.value }
                    // v1.2.23：流式搜——每源出结果就地追加，不等 2000+ 源全轮跑完；
                    // 首批 1~2 秒即现。v1.1.12 语义保留：旧结果留到首批新结果到来才
                    // 替换；全轮无结果则旧结果照旧留着，只置失败汇总。
                    // v1.2.24：阅读式 500ms 节流——2000+ 源逐个追加会让列表每秒重组
                    // 几百次；先攒着，每 400ms 批量刷一次（首批立即刷）。
                    var firstBatch = true
                    var lastFlushMs = 0L
                    val pending = mutableListOf<SourceGroup>()
                    fun append(groups: List<SourceGroup>) {
                        if (groups.isEmpty()) return
                        _results.value = if (firstBatch) {
                            firstBatch = false
                            groups
                        } else {
                            _results.value + groups
                        }
                    }
                    fun flushPending() {
                        if (pending.isEmpty()) return
                        append(pending.toList())
                        pending.clear()
                        lastFlushMs = System.currentTimeMillis()
                    }
                    coroutineScope {
                        val flowJob = launch {
                            NovelSourceEngine.searchAllFlow(
                                enabledSources, kw, precision = _precisionSearch.value,
                            ).collect { res ->
                                _filteredCount.value += res.filtered
                                if (res.books.isNotEmpty()) {
                                    pending.add(SourceGroup(res.source.key, res.source.name, res.books))
                                    if (System.currentTimeMillis() - lastFlushMs >= 400) flushPending()
                                }
                            }
                            flushPending()
                        }
                        val pyJob = async {
                            NovelSourceEngine.searchAllPy(getApplication(), enabledPyRules, kw)
                                .filter { it.second.isNotEmpty() }
                                .map { (r, books) ->
                                    SourceGroup(
                                        PyBookSourceManager.SOURCE_KEY_PREFIX + r.name,
                                        "${r.title}（本地PY）",
                                        books,
                                    )
                                }
                        }
                        append(pyJob.await())
                        flowJob.join()
                    }
                }
            // v1.1.12：搜完读一遍失败汇总——有失败才置标，无失败保持 null。
            _failedLabel.value = NovelSourceEngine.failedSearchLabel()
            } catch (e: CancellationException) {
                // 被取消（离页复位/新搜索顶掉）不留半截或旧结果；只清自己这一轮，不误清新一轮。
                if (searchJob === myJob) _results.value = emptyList()
                throw e
            } catch (e: Exception) {
                // v1.1.12：整轮搜书抛异常也不清空旧结果——顶行明说这次失败，显示的是上次的结果。
                _failedLabel.value = NovelSourceEngine.failedSearchLabel() ?: "搜书失败了"
            } finally {
                _loading.value = false
            }
        }
    }

    /** v0.249.0：先入架后补目录——点加入先校验去重、立即本地入架（空目录壳），
     * 按钮立刻变“已在书架”、书架马上可见；目录在应用级作用域后台补取，
     * 离开找书页继续跑，失败书留架标 FAILED、可在书架重试。
     * 加入中只覆盖本地写入的短暂过程；入架被取消时原样抛出，不当成加入失败。 */
    fun joinShelf(group: SourceGroup, item: NovelSearchResult) {
        val key = group.key + item.remoteId
        viewModelScope.launch {
            _joining.value = _joining.value + key
            // 修推：结果须在 try 外声明——Toast 在 try-finally 之后引用它，
            // 放 try 里会报 Unresolved reference（run #423 失败根因）。
            var result = ""
            try {
                result = runCatching {
                    // v0.216.0（C10）：id 走新稳定拼法；书架里若已有同源同书
                    // （新 id 或老 hash id），复用旧 id，不产生第二本。
                    val newId = shelfNovelId(group, item)
                    val legacyId = "bs_${group.key.hashCode()}_${item.remoteId.hashCode()}"
                    val existing = NovelStore.list(getApplication()).firstOrNull {
                        it.sourceKey == group.key && (it.id == newId || it.id == legacyId)
                    }
                    if (existing != null) {
                        // 已在架：不重复建壳；壳状态未完成时顺手触发一次精确补取。
                        if (existing.dirState != NovelStore.DIR_READY) {
                            NovelDirectoryFill.fillExact(getApplication(), group.key, item, existing.id)
                        }
                        return@runCatching "已在书架"
                    }
                    val novel = Novel(
                        id = newId,
                        sourceKey = group.key,
                        title = item.title,
                        author = item.author,
                        cover = item.cover,
                        addedAt = System.currentTimeMillis(),
                        remoteId = item.remoteId,
                    )
                    if (!NovelStore.insertShell(getApplication(), novel)) {
                        // 并发抢入：另一路已先建壳，按已在架处理。
                        return@runCatching "已在书架"
                    }
                    NovelDirectoryFill.fillExact(getApplication(), group.key, item, newId)
                    "已加入书架"
                }.onFailure {
                    // v0.247.0（取消语义）：入架被取消时原样抛出，不当成
                    // “加入失败”弹 Toast；finally 照常摘掉加入中标记。
                    if (it is CancellationException) throw it
                }.getOrDefault("加入失败")
            } finally {
                _joining.value = _joining.value - key
            }
            AppToast.show(getApplication(), result, long = false)
        }
    }
}
