package com.movieshell.ui.novel

import android.app.Application
import com.movieshell.ui.components.AppToast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movieshell.data.model.Novel
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.repo.NovelDirectoryFill
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.SearchHistoryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
            _keyword.value = ""
        }
    }

    fun setKeyword(k: String) {
        _keyword.value = k
    }

    // v0.231.0：重进找书不留上次记录——清结果＋清已搜标记，在途搜索一并取消，免得旧结果晚一步又填回来。
    fun clearResults() {
        searchJob?.cancel()
        searchJob = null
        _results.value = emptyList()
        _searched.value = false
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
        searchJob = viewModelScope.launch {
            try {
                _loading.value = true
                _searched.value = true
                _results.value = coroutineScope {
                // v0.66.0：锁定单 PY 源 → 只搜它
                if (locked != null) {
                    NovelSourceEngine.searchAllPy(getApplication(), listOf(locked), kw)
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
                    val jsonJob = async {
                        NovelSourceEngine.searchAll(enabledSources, kw, allowGate = true)
                            .filter { it.second.isNotEmpty() }
                            .map { (s, books) -> SourceGroup(s.key, s.name, books) }
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
                    jsonJob.await() + pyJob.await()
                }
            }
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
