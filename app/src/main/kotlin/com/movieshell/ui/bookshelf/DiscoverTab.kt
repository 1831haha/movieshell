package com.movieshell.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.novel.BookProbeStore
import com.movieshell.data.novel.BookProbe
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSourceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.248.0：发现内页（书架子结构，参考图发现页布局）。
 * - 第一层：书源列表行（状态点＋书源名＋状态文案＋右箭头），点行进分类层；
 *   状态点是小说源轻量连通探测结果，不是评分，绝不编等级。
 * - 第二层：回退＋源名＋男生／女生／其他分组＋分类胶囊，点胶囊进该源发现页
 *   （走现有 ExploreScreen，分类预选）。
 * - 分类分组是 App 展示映射（书源分类本身没有性别维度），不是书源数据；
 *   映射表见 discoverGenderGroups。
 */

/** 发现页：书源下拉的平替——先列源、点行再看分类，切换源即回列表重选。 */
@Composable
fun DiscoverTab(
    onOpenExplore: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    val sources by BookSourceManager.sources.collectAsState()
    val probeMap by BookProbeStore.map.collectAsState()
    var selectedKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            BookSourceManager.load(context)
            BookProbeStore.load(context)
        }
    }

    // 只聚带发现分类的已启用 JSON 书源；PY 源没有发现分类体系，不进发现页。
    val aggSources = sources.filter { it.enabled && it.exploreUrl.isNotBlank() }

    // 进页即探：只探未知、地址对不上（源地址改过，旧记录作废）或超过 24 小时的源，
    // 离页自动取消在途探测；取消按取消处理，绝不写成“不通”。
    LaunchedEffect(aggSources) {
        val now = System.currentTimeMillis()
        aggSources.forEach { src ->
            if (src.url.isBlank()) return@forEach
            val rec = probeMap[src.key]
            if (rec != null && rec.url == src.url && now - rec.time < 24L * 60 * 60 * 1000) return@forEach
            launch {
                val r = BookProbe.probe(src.url)
                BookProbeStore.update(context, src.key, r)
            }
        }
    }

    val selected = aggSources.firstOrNull { it.key == selectedKey }
    if (selected == null) {
        // 第一层：书源列表。
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = com.movieshell.ui.components.dockClearance() + 8.dp),
        ) {
            if (aggSources.isEmpty()) {
                item {
                    Text(
                        "没有带发现分类的已启用书源：去书源管理启用带发现地址的源。",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            } else {
                items(aggSources, key = { it.key }) { src ->
                    val rec = probeMap[src.key]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedKey = src.key }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(probeDotColor(rec?.status), CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = src.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = probeStatusText(rec?.status),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    } else {
        // 第二层：该源的分类胶囊（男生／女生／其他）。
        val categories = remember(selected) {
            NovelSourceEngine.parseExploreCategories(selected.exploreUrl)
        }
        val groups = remember(categories) {
            discoverGenderGroups(categories.map { it.title })
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedKey = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书源列表")
                }
                Text(
                    text = selected.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            if (categories.isEmpty()) {
                Text(
                    "该源没有发现分类。",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = com.movieshell.ui.components.dockClearance() + 8.dp),
                ) {
                    groups.forEach { (groupTitle, titles) ->
                        item(key = "g-$groupTitle") {
                            Text(
                                text = groupTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                            )
                        }
                        item(key = "caps-$groupTitle") {
                            DiscoverCapsules(
                                titles = titles,
                                onClick = { title -> onOpenExplore(selected.key, title) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分类胶囊行：浅底圆角胶囊多行排布，点胶囊即进该分类发现页。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverCapsules(
    titles: List<String>,
    onClick: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        titles.forEach { title ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onClick(title) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(text = title, fontSize = 13.sp)
            }
        }
    }
}

/** 探测状态点颜色：通绿、慢黄、不通红、未测灰。 */
private fun probeDotColor(status: String?): Color = when (status) {
    "OK" -> Color(0xFF3DD68C)
    "SLOW" -> Color(0xFFE8B45A)
    "FAIL" -> Color(0xFFFF5A5F)
    else -> Color(0xFF6B7385)
}

/** 探测状态文案：只陈述连通结果，不含任何评分含义。 */
private fun probeStatusText(status: String?): String = when (status) {
    "OK" -> "通"
    "SLOW" -> "慢"
    "FAIL" -> "不通"
    else -> "未测"
}

// 展示分组映射（App 侧配置，不是书源数据）：分类词本身是书源带的，
// 这里只按参考图原样分男／女两组，不另行取舍——
// - 女侧：言情系与参考图女生列常见词；
// - 男侧：玄幻仙侠科幻军事等参考图男生列常见词；
// - 穿越／重生／都市两边都常见，按参考图做法同时进两组；
// - 两边标记都命中（如玄幻言情）同样进两组；
// - 都不命中进“其他”，有内容时才显示该组。
private val DISCOVER_FEMALE_KEYS = listOf(
    "言情", "总裁", "豪门", "宫斗", "宅斗", "女尊", "女强",
    "甜宠", "宠文", "婚恋", "耽美", "短篇", "年下", "空间", "系统", "女频", "女生",
)
private val DISCOVER_MALE_KEYS = listOf(
    "玄幻", "仙侠", "修真", "武侠", "科幻", "末世", "无限", "游戏", "电竞",
    "军事", "历史", "体育", "竞技", "悬疑", "灵异", "惊悚", "官场", "商战",
    "异能", "洪荒", "封神", "医生", "战神", "女婿", "皇帝", "弃少", "热血",
    "爽文", "强者", "鉴宝", "签到", "复仇", "丹药", "血族", "现实", "影视", "二次元",
)
private val DISCOVER_BOTH_KEYS = listOf("穿越", "重生", "都市")

/** 分类标题按展示映射分男生／女生／其他三组（保持源内顺序、组内去重）。 */
private fun discoverGenderGroups(titles: List<String>): List<Pair<String, List<String>>> {
    val male = mutableListOf<String>()
    val female = mutableListOf<String>()
    val other = mutableListOf<String>()
    titles.forEach { title ->
        val inMale = DISCOVER_MALE_KEYS.any { title.contains(it) }
        val inFemale = DISCOVER_FEMALE_KEYS.any { title.contains(it) }
        val inBoth = DISCOVER_BOTH_KEYS.any { title.contains(it) }
        when {
            inBoth || (inMale && inFemale) -> {
                male += title
                female += title
            }
            inMale -> male += title
            inFemale -> female += title
            else -> other += title
        }
    }
    return listOf(
        "男生" to male.distinct(),
        "女生" to female.distinct(),
        "其他" to other.distinct(),
    ).filter { it.second.isNotEmpty() }
}
