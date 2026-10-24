package com.movieshell.ui.bookshelf

import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movieshell.data.model.Novel
import com.movieshell.data.model.ReadingProgress
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelUpdateChecker
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.novel.TxtImporter
import com.movieshell.data.repo.BookGroup
import com.movieshell.data.repo.BookshelfGroupStore
import com.movieshell.data.repo.NovelStore
import com.movieshell.ui.novel.NovelSearchScreen
import com.movieshell.ui.novel.NovelSearchViewModel
import com.movieshell.data.repo.NovelUpdateInfo
import com.movieshell.data.repo.NovelUpdateStore
import com.movieshell.data.repo.ReadingProgressStore
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.goldTitleColor
import com.movieshell.ui.market.PluginUi
import com.movieshell.ui.theme.LocalBrandGradient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.98.0：书架重设计（专业 + 可玩性）。
 * - 分组：智能分组（全部/在读/养肥/已读完）+ 用户自定义分组（外部映射表，不动 Novel 本体）。
 * - 双视图（列表/网格）+ 排序（最近阅读/更新时间/书名/阅读进度），偏好持久化。
 * - 批量多选管理（删除/移入分组）；长按操作表；书籍详情弹窗。
 * - 趣味空状态；按压缩放/角标脉冲/分组切换动画微交互。
 * v0.178.0：阅读数据条整块去掉；顶部小按钮只留搜索/添加本地/书源管理；
 * 检查更新改成下拉刷新；长按操作表加全选/分组/收藏。
 * v0.179.0：智能分组（在读/养肥/已读完）去掉，只留全部 + 自定义分组；
 * 顶部只留搜索在外，添加本地/书源管理进右上角三点菜单（参考阅读布局）。
 * v0.180.0：全部提真顶行左侧，最近阅读行提顶行下面、自定义分组上面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onNovelClick: (String) -> Unit = {},
    onManageSourcesClick: () -> Unit = {},
    // v0.98.0：书籍详情里的净化规则快捷入口
    onOpenPurifyRules: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var books by remember { mutableStateOf<List<Novel>>(emptyList()) }
    var progressMap by remember { mutableStateOf<Map<String, ReadingProgress>>(emptyMap()) }
    var updates by remember { mutableStateOf<Map<String, NovelUpdateInfo>>(emptyMap()) }
    var customGroups by remember { mutableStateOf<List<BookGroup>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    // v0.98.0：视图/排序/分组偏好
    var viewMode by remember { mutableStateOf(BookshelfGroupStore.viewMode(context)) }
    var sortMode by remember { mutableStateOf(BookshelfGroupStore.sortMode(context)) }
    var selectedGroup by remember { mutableStateOf(BookshelfGroupStore.selectedGroup(context)) }

    // v0.197.0：阅读式页签（全部 / 更新失败）+ 上次检查失败的书 id（持久化在 prefs）
    var shelfTab by remember { mutableStateOf(0) }
    var failedIds by remember { mutableStateOf(BookshelfGroupStore.failedIds(context)) }

    // v0.98.0：多选模式
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // v0.195.0：书架内直接搜（不用单独找书页）
    var searching by remember { mutableStateOf(false) }

    // 弹窗状态
    var deleteTarget by remember { mutableStateOf<Novel?>(null) }
    var batchDelete by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<Novel?>(null) }
    var detailTarget by remember { mutableStateOf<Novel?>(null) }
    var groupPickerFor by remember { mutableStateOf<List<String>?>(null) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BookGroup?>(null) }
    var groupMenuTarget by remember { mutableStateOf<BookGroup?>(null) }

    fun reload() {
        scope.launch {
            val all = withContext(Dispatchers.IO) { NovelStore.list(context) }
            val pm = withContext(Dispatchers.IO) {
                all.associate { novel ->
                    novel.id to (ReadingProgressStore.load(context, novel.id)
                        ?: ReadingProgress(novelId = novel.id))
                }
            }
            val ups = withContext(Dispatchers.IO) { NovelUpdateStore.getAll(context) }
            val gs = withContext(Dispatchers.IO) { BookshelfGroupStore.list(context) }
            progressMap = pm
            updates = ups
            customGroups = gs
            // 分组被删了就回全部（v0.179.0：智能分组已去掉，
            // 以前选过在读/养肥/已读完的也回全部）
            val validIds = setOf(
                BookshelfGroupStore.GROUP_ALL,
            ) + gs.map { it.id }
            if (selectedGroup !in validIds) {
                selectedGroup = BookshelfGroupStore.GROUP_ALL
                BookshelfGroupStore.setSelectedGroup(context, selectedGroup)
            }
            books = all
            // 删书后多选集合里可能残留不存在的 id，顺手清理
            selectedIds = selectedIds.filter { id -> all.any { it.id == id } }.toSet()
        }
    }
    LaunchedEffect(Unit) { reload() }

    // v0.84.0：检查全部在线书的更新（本地 TXT 跳过）
    fun checkUpdates() {
        if (checking) return
        checking = true
        scope.launch {
            withContext(Dispatchers.IO) {
                BookSourceManager.load(context)
                PyBookSourceManager.load(context)
            }
            // v0.186.0 第 9 组：用带失败计数的批量检查——全失败时不再谎报“都追上了”
            val checked = NovelUpdateChecker.checkAllDetailed(context, books)
            val result = checked.updated
            result.forEach { (id, n) -> NovelUpdateStore.set(context, id, n) }
            // v0.197.0：失败 id 存起来供“更新失败”页签用
            failedIds = checked.failedIds
            BookshelfGroupStore.setFailedIds(context, checked.failedIds)
            checking = false
            reload()
            PluginUi.toast(context, 
                when {
                    result.isNotEmpty() -> "${result.size} 本有更新"
                    checked.checked > 0 && checked.failed == checked.checked -> "检查失败，稍后再试"
                    checked.failed > 0 -> "已检查，${checked.failed} 本检查失败"
                    else -> "都追上了，没有新章节"
                },
            )
        }
    }

    fun openNovel(novel: Novel) {
        scope.launch {
            NovelUpdateStore.clear(context, novel.id)
            updates = updates - novel.id
        }
        onNovelClick(novel.id)
    }

    fun deleteNovels(targets: List<Novel>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                // v0.186.0 第 9 组：走删书唯一入口，与搜索页清理范围一致
                // （本体 + 进度 + 书签 + 分组 + 追更角标）
                targets.forEach { t ->
                    NovelStore.deleteNovelCascade(context, t.id)
                }
            }
            selectionMode = false
            selectedIds = emptySet()
            PluginUi.toast(context, "已删除 ${targets.size} 本")
            reload()
        }
    }

    fun moveToGroup(group: BookGroup, novelIds: List<String>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                BookshelfGroupStore.addNovels(context, group.id, novelIds)
            }
            groupPickerFor = null
            selectionMode = false
            selectedIds = emptySet()
            PluginUi.toast(context, "已移入「${group.name}」")
            reload()
        }
    }

    // v0.178.0：收藏不另建表，就是自定义分组里一个叫“收藏”的分组，
    // 没有就现建，已在里面就取消，删书时随分组映射一起清。
    fun toggleFavorite(novel: Novel) {
        scope.launch {
            var fav = BookshelfGroupStore.list(context).find { it.name == "收藏" }
            if (fav == null) {
                fav = BookshelfGroupStore.create(context, "收藏")
                if (fav.id.isBlank()) {
                    PluginUi.toast(context, "收藏失败")
                    return@launch
                }
            }
            val isFav = fav.novelIds.contains(novel.id)
            if (isFav) {
                BookshelfGroupStore.removeNovels(context, fav.id, listOf(novel.id))
            } else {
                BookshelfGroupStore.addNovels(context, fav.id, listOf(novel.id))
            }
            PluginUi.toast(context, if (isFav) "已取消收藏" else "已收藏")
            reload()
        }
    }

    // ---------------- 数据计算 ----------------

    data class ShelfGroup(val id: String, val name: String, val count: Int)

    // v0.179.0：只留全部（放最前面）+ 自定义分组，智能分组已删。
    val shelfGroups = remember(books, customGroups) {
        listOf(
            ShelfGroup(BookshelfGroupStore.GROUP_ALL, "全部", books.size),
        ) + customGroups.map { g ->
            ShelfGroup(g.id, g.name, books.count { g.novelIds.contains(it.id) })
        }
    }

    val visibleBooks = remember(books, selectedGroup, sortMode, progressMap, updates, customGroups, shelfTab, failedIds) {
        // v0.197.0：更新失败页签只列上次检查失败的书（不看分组）；全部页签沿用分组筛选
        val preFiltered = if (shelfTab == 1) books.filter { it.id in failedIds } else books
        val filtered = if (shelfTab == 1) preFiltered else when (selectedGroup) {
            BookshelfGroupStore.GROUP_ALL -> preFiltered
            else -> {
                val ids = customGroups.find { it.id == selectedGroup }?.novelIds?.toSet()
                    ?: emptySet()
                preFiltered.filter { it.id in ids }
            }
        }
        when (sortMode) {
            BookshelfGroupStore.SORT_UPDATED ->
                filtered.sortedWith(
                    compareByDescending<Novel> { updates[it.id]?.checkedAt ?: 0L }
                        .thenByDescending { progressMap[it.id]?.updatedAt ?: 0L },
                )
            BookshelfGroupStore.SORT_TITLE -> filtered.sortedBy { it.title }
            BookshelfGroupStore.SORT_PROGRESS -> filtered.sortedByDescending { it.readFraction() }
            else -> filtered.sortedByDescending { progressMap[it.id]?.updatedAt ?: 0L }
        }
    }

    fun toggleSelect(id: String) {
        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
    }

    val sortNames = listOf("最近阅读", "更新时间", "书名", "阅读进度")
    var showSortMenu by remember { mutableStateOf(false) }
    // v0.179.0：右上角三点菜单（添加本地/书源管理）。
    var showTopMenu by remember { mutableStateOf(false) }
    // v0.178.0：下拉刷新状态，复用首页同款官方 PullToRefreshBox。
    val pullRefreshState = rememberPullToRefreshState()

    val pickTxt = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    } ?: "导入小说.txt"
                    val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.txt")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: return@runCatching false
                    val named = File(tmp.parent, name)
                    tmp.renameTo(named)
                    val imported = TxtImporter.import(named) ?: return@runCatching false
                    named.delete()
                    NovelStore.addNovel(context, imported.novel, imported.chapters)
                }.getOrDefault(false)
            }
            importing = false
            PluginUi.toast(context, if (ok) "已加入书架" else "导入失败：不是有效的 TXT")
            if (ok) reload()
        }
    }

    // ---------------- 界面 ----------------

    // v0.177.0：书架页正常模式顶部标题栏整行去掉——数据条直接贴顶（仅留状态栏高度）；
    // 多选模式的“已选 N 本”标题栏保留，那是批量操作的操作栏，不是正常顶栏。
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        if (selectionMode) {
            TabTopBar(
                title = "已选 ${selectedIds.size} 本",
                actions = {
                    TextButton(
                        onClick = {
                            selectedIds = visibleBooks.map { it.id }.toSet()
                        },
                    ) { Text("全选") }
                    TextButton(
                        onClick = { if (selectedIds.isNotEmpty()) batchDelete = true },
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Text("删除", color = Color(0xFFE53935))
                    }
                    TextButton(
                        onClick = {
                            if (selectedIds.isNotEmpty()) groupPickerFor = selectedIds.toList()
                        },
                        enabled = selectedIds.isNotEmpty(),
                    ) { Text("分组") }
                    IconButton(onClick = {
                        selectionMode = false
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Filled.Close, "取消多选")
                    }
                },
            )
        }
        // v0.180.0：顶行左侧放“全部”，右侧放搜索 + 三点菜单；
        // 最近阅读那一行同步提上来，放在顶行下面、自定义分组上面。
        // 检查更新改下拉刷新、批量管理改长按进多选；
        // 多选模式仍用上面那个“已选 N 本”操作栏。

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // v0.197.0：顶行左侧改阅读式页签（全部 / 更新失败，选中带主题色下划线）；
            // 179 的“全部”分组胶囊并入页签，不再重复出现。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(0 to "全部", 1 to "更新失败").forEachIndexed { i, (tab, label) ->
                        if (i > 0) Spacer(Modifier.width(18.dp))
                        val tabSelected = shelfTab == tab
                        val tabText = if (tab == 1 && failedIds.isNotEmpty()) "$label(${failedIds.size})" else label
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { shelfTab = tab },
                        ) {
                            Text(
                                tabText,
                                fontSize = 15.sp,
                                fontWeight = if (tabSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (tabSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(3.dp))
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (tabSelected) MaterialTheme.colorScheme.primary
                                        else Color.Transparent,
                                    ),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { searching = true }) {
                        Icon(Icons.Filled.Search, "找书")
                    }
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(Icons.Filled.MoreVert, "更多")
                        }
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false },
                        ) {
                            // v0.197.0：阅读式书架菜单（只留有真实功能的项；
                            // 添加网址/远程书籍/缓存导出/导出导入书单/日志暂无对应功能，不摆空壳）
                            DropdownMenuItem(
                                text = { Text("更新目录") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Refresh, contentDescription = null)
                                },
                                onClick = {
                                    showTopMenu = false
                                    checkUpdates()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("添加本地书籍") },
                                enabled = !importing,
                                leadingIcon = {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                },
                                onClick = {
                                    showTopMenu = false
                                    pickTxt.launch("text/plain")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("书架管理") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Checklist, contentDescription = null)
                                },
                                onClick = {
                                    showTopMenu = false
                                    selectionMode = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("书架布局") },
                                leadingIcon = {
                                    Icon(Icons.Filled.GridView, contentDescription = null)
                                },
                                onClick = {
                                    showTopMenu = false
                                    viewMode = if (viewMode == BookshelfGroupStore.VIEW_LIST)
                                        BookshelfGroupStore.VIEW_GRID else BookshelfGroupStore.VIEW_LIST
                                    BookshelfGroupStore.setViewMode(context, viewMode)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("书源管理") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Storage, contentDescription = null)
                                },
                                onClick = {
                                    showTopMenu = false
                                    onManageSourcesClick()
                                },
                            )
                        }
                    }
                }
            }
            // v0.196.0：搜索框上移到与“找书”同一行——框直接绑活动级搜索 VM
            // （与内嵌找书页同一份 keyword），回车即搜；VM 与焦点提到分支外，避免条件组合。
            val searchActivity = LocalContext.current as ComponentActivity
            val searchVm: NovelSearchViewModel = viewModel(searchActivity)
            val searchKeyword by searchVm.keyword.collectAsState()
            val searchFocus = remember { FocusRequester() }
            if (searching) {
                LaunchedEffect(Unit) { searchFocus.requestFocus() }
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "找书",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            value = searchKeyword,
                            onValueChange = { searchVm.setKeyword(it) },
                            placeholder = { Text("书名 / 作者") },
                            singleLine = true,
                            shape = RoundedCornerShape(50),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { searchVm.doSearch() }),
                            modifier = Modifier.weight(1f).focusRequester(searchFocus),
                        )
                        TextButton(onClick = { searching = false }) { Text("取消") }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        NovelSearchScreen(
                            embedded = true,
                            autoFocus = false,
                            hideSearchField = true,
                            onManageSources = onManageSourcesClick,
                        )
                    }
                }
            } else {
            Spacer(Modifier.height(2.dp))

            // v0.180.0：最近阅读这一行同步提上来，放在顶行下面、分组上面。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showSortMenu = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Sort,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            sortNames.getOrElse(sortMode) { sortNames[0] },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        sortNames.forEachIndexed { i, name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    showSortMenu = false
                                    sortMode = i
                                    BookshelfGroupStore.setSortMode(context, i)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${visibleBooks.size} 本",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        viewMode = if (viewMode == BookshelfGroupStore.VIEW_LIST)
                            BookshelfGroupStore.VIEW_GRID else BookshelfGroupStore.VIEW_LIST
                        BookshelfGroupStore.setViewMode(context, viewMode)
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        if (viewMode == BookshelfGroupStore.VIEW_LIST) Icons.Filled.GridView
                        else Icons.Filled.FormatListBulleted,
                        contentDescription = "切换视图",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // v0.180.0：自定义分组横滑（全部已提顶行，不在这里重复）。
            // v0.197.0：更新失败页签不看分组，横滑行隐藏。
            if (shelfTab == 0) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 4.dp),
            ) {
                items(shelfGroups.filter { it.id != BookshelfGroupStore.GROUP_ALL }, key = { it.id }) { g ->
                    val selected = g.id == selectedGroup
                    GroupChip(
                        name = g.name,
                        count = g.count,
                        selected = selected,
                        onClick = {
                            selectedGroup = g.id
                            BookshelfGroupStore.setSelectedGroup(context, g.id)
                        },
                        onLongClick = {
                            // 自定义分组长按：重命名/删除
                            customGroups.find { it.id == g.id }?.let { groupMenuTarget = it }
                        },
                    )
                }
                item(key = "add_group") {
                    GroupChip(
                        name = "+ 分组",
                        count = -1,
                        selected = false,
                        onClick = { showCreateGroup = true },
                        onLongClick = null,
                    )
                }
            }
            } // v0.197.0：闭合 shelfTab==0 的分组横滑
            Spacer(Modifier.height(8.dp))

            // v0.178.0：下拉刷新 = 原检查更新（获取最新章节），
            // checking 既是刷新指示也是防重入，空书架无书可查不触发。
            PullToRefreshBox(
                state = pullRefreshState,
                isRefreshing = checking,
                onRefresh = { checkUpdates() },
                modifier = Modifier.fillMaxSize(),
            ) {
            // v0.98.0：内容区（分组切换小动画）
            AnimatedContent(
                targetState = "$selectedGroup|$viewMode|$shelfTab",
                label = "shelfContent",
                modifier = Modifier.fillMaxSize(),
            ) {
                if (books.isEmpty()) {
                    // 趣味空状态
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            BookshelfEmptyState(
                                onFindBook = { searching = true },
                                onImportTxt = { pickTxt.launch("text/plain") },
                            )
                        }
                    }
                } else if (visibleBooks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            // v0.197.0：更新失败页签空态单独说明
                            if (shelfTab == 1) "还没有更新失败的书\n下拉刷新检查后，失败的会列在这里"
                            else "这个分组还没有书\n长按书籍可全选/分组/收藏",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp,
                        )
                    }
                } else if (viewMode == BookshelfGroupStore.VIEW_GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        if (checking) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                CheckingRow()
                            }
                        }
                        items(visibleBooks, key = { it.id }) { novel ->
                            NovelGridCard(
                                novel = novel,
                                updateCount = updates[novel.id]?.newChapters ?: 0,
                                selectionMode = selectionMode,
                                selected = selectedIds.contains(novel.id),
                                onClick = {
                                    if (selectionMode) toggleSelect(novel.id)
                                    else openNovel(novel)
                                },
                                onLongClick = {
                                    if (selectionMode) toggleSelect(novel.id)
                                    else actionTarget = novel
                                },
                            )
                        }
                    }
                } else {
                    // v0.197.0：阅读式 plain 行自带底部分隔线，列表不再额外留 10dp 间距
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        if (checking) {
                            item(key = "checking") { CheckingRow() }
                        }
                        items(visibleBooks, key = { it.id }) { novel ->
                            NovelListCard(
                                novel = novel,
                                lastReadAt = progressMap[novel.id]?.updatedAt ?: 0L,
                                updateCount = updates[novel.id]?.newChapters ?: 0,
                                selectionMode = selectionMode,
                                selected = selectedIds.contains(novel.id),
                                onClick = {
                                    if (selectionMode) toggleSelect(novel.id)
                                    else openNovel(novel)
                                },
                                onLongClick = {
                                    if (selectionMode) toggleSelect(novel.id)
                                    else actionTarget = novel
                                },
                            )
                        }
                    }
                }
            }
            } // v0.178.0：闭合 PullToRefreshBox
            }
        }
    }

    // ---------------- 弹窗 ----------------

    // 单删确认
    deleteTarget?.let { target ->
        TechDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除《${target.title}》？",
            confirmText = "删除",
            onConfirm = {
                deleteTarget = null
                deleteNovels(listOf(target))
            },
        ) {
            Text("将从书架移除，阅读进度、书签与追更标记也会清除。")
        }
    }

    // 批量删除确认
    if (batchDelete) {
        TechDialog(
            onDismissRequest = { batchDelete = false },
            title = "删除 ${selectedIds.size} 本书？",
            confirmText = "删除",
            onConfirm = {
                batchDelete = false
                deleteNovels(books.filter { selectedIds.contains(it.id) })
            },
        ) {
            Text("将从书架移除，阅读进度、书签与追更标记也会清除。")
        }
    }

    // 长按操作表（v0.178.0：新增全选/收藏，分组沿用移入分组）
    actionTarget?.let { target ->
        BookActionSheet(
            novel = target,
            isFavorite = customGroups.find { it.name == "收藏" }
                ?.novelIds?.contains(target.id) == true,
            onDismiss = { actionTarget = null },
            onRead = { actionTarget = null; openNovel(target) },
            onDetail = { actionTarget = null; detailTarget = target },
            onMoveGroup = { actionTarget = null; groupPickerFor = listOf(target.id) },
            onChangeSource = { actionTarget = null; detailTarget = target },
            onDelete = { actionTarget = null; deleteTarget = target },
            onSelectAll = {
                actionTarget = null
                selectionMode = true
                selectedIds = visibleBooks.map { it.id }.toSet()
            },
            onToggleFavorite = { actionTarget = null; toggleFavorite(target) },
        )
    }

    // 书籍详情
    detailTarget?.let { target ->
        // 用最新数据（换源后 totalChapters 可能变）
        val fresh = books.find { it.id == target.id } ?: target
        BookDetailDialog(
            novel = fresh,
            lastReadAt = progressMap[fresh.id]?.updatedAt ?: 0L,
            onDismiss = { detailTarget = null },
            onRead = { detailTarget = null; openNovel(fresh) },
            onMoveGroup = { detailTarget = null; groupPickerFor = listOf(fresh.id) },
            onDelete = { detailTarget = null; deleteTarget = fresh },
            onOpenPurifyRules = { detailTarget = null; onOpenPurifyRules() },
            onSourceSwitched = {
                PluginUi.toast(context, "已换源")
                reload()
            },
        )
    }

    // 分组选择（移入）
    groupPickerFor?.let { ids ->
        GroupPickerDialog(
            groups = customGroups,
            onDismiss = { groupPickerFor = null },
            onPick = { g ->
                val group = customGroups.find { it.id == g.id } ?: g
                moveToGroup(group, ids)
            },
            onCreateNew = { showCreateGroup = true },
        )
    }

    // 新建分组
    if (showCreateGroup) {
        GroupNameDialog(
            title = "新建分组",
            onDismiss = { showCreateGroup = false },
            onConfirm = { name ->
                showCreateGroup = false
                scope.launch {
                    val g = withContext(Dispatchers.IO) {
                        BookshelfGroupStore.create(context, name)
                    }
                    if (g.id.isNotBlank()) {
                        // 从分组选择器进来时，建完直接移入
                        val ids = groupPickerFor
                        reload()
                        if (ids != null) moveToGroup(g, ids) else PluginUi.toast(context, "已创建「${g.name}」")
                    }
                }
            },
        )
    }

    // 自定义分组长按菜单：重命名/删除
    groupMenuTarget?.let { g ->
        TechDialog(
            onDismissRequest = { groupMenuTarget = null },
            title = "「${g.name}」",
            onConfirm = null,
            dismissText = "取消",
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            groupMenuTarget = null
                            renameTarget = g
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("重命名", fontSize = 15.sp)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            groupMenuTarget = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    BookshelfGroupStore.delete(context, g.id)
                                }
                                if (selectedGroup == g.id) {
                                    selectedGroup = BookshelfGroupStore.GROUP_ALL
                                    BookshelfGroupStore.setSelectedGroup(context, selectedGroup)
                                }
                                PluginUi.toast(context, "已删除分组（书籍保留）")
                                reload()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("删除分组", fontSize = 15.sp, color = Color(0xFFE53935))
                }
                Text(
                    "删除分组不会删除书籍",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }

    // 重命名分组
    renameTarget?.let { g ->
        GroupNameDialog(
            title = "重命名分组",
            initial = g.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        BookshelfGroupStore.rename(context, g.id, name)
                    }
                    reload()
                }
            },
        )
    }
}

/** v0.98.0：分组 chip（长按自定义分组弹出重命名/删除）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupChip(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val borderBrush = Brush.horizontalGradient(LocalBrandGradient.current)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (selected) Modifier.border(
                    BorderStroke(1.5.dp, borderBrush),
                    RoundedCornerShape(16.dp),
                )
                else Modifier.border(
                    BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    ),
                    RoundedCornerShape(16.dp),
                ),
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) goldTitleColor()
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count >= 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                "$count",
                fontSize = 11.sp,
                color = if (selected) goldTitleColor().copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** v0.84.0：检查中提示条（搬运）。 */
@Composable
private fun CheckingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "正在检查更新…",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
