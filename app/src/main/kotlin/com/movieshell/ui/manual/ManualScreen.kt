package com.movieshell.ui.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 手册章节：标题 + 3~5 条口语化要点。内容是代码常量，不读 assets。 */
private data class ManualSection(
    val title: String,
    val points: List<String>,
)

private val MANUAL_SECTIONS = listOf(
    ManualSection(
        title = "加站源",
        points = listOf(
            "点「站源」页顶上的「添加订阅」，粘贴 TVBox 订阅地址（接口站源）就行。",
            "苹果 CMS 的 JSON 接口（type=1）和老 XML 接口（type=0）会自动识别，不用你选。",
            "加完回到首页，下拉刷新就能看到新站的推荐。",
            "订阅地址失效了？删掉这条，换个能用的重新添加。",
        ),
    ),
    ManualSection(
        title = "JS 规则",
        points = listOf(
            ".js 规则文件放到站源页的「JS文件夹」，列表里能看到。",
            "点一条就是「切换当前规则」，首页、搜索都走它。",
            "搜不到片先看看是不是规则切错了，别急着删文件。",
            "规则是纯文本，坏了删掉，重新放一个进去就行。",
        ),
    ),
    ManualSection(
        title = "PY 规则",
        points = listOf(
            ".py 规则（hipy 风格）放到「PY文件夹」，点一下设为当前规则。",
            "第一次用 PY 规则时会初始化 Python 引擎，等个 1~2 秒，之后就快了。",
            "引擎初始化好会一直复用，不用每次都等。",
            "个别规则缺解密库会直接报错，不耽误其他规则用。",
        ),
    ),
    ManualSection(
        title = "磁力播放",
        points = listOf(
            "magnet: 开头的链接点播放会自动走本地转播，不用你手动复制。",
            "前提：手机上装好 magnet-stream 转播服务，并且是启动状态（127.0.0.1:17890）。",
            "服务没开会弹提示，先去把服务打开，再回来点播放。",
            "磁力靠做种人数，冷门资源多等一会儿再播，别频繁重播。",
        ),
    ),
    ManualSection(
        title = "搜索 / 详情 / 播放",
        points = listOf(
            "搜索页输片名，搜的是当前激活的那条规则。",
            "详情页有简介、演员和分集列表，点集数直接播。",
            "看过的进度会自动记住，下次点开接着看。",
            "搜过的词会留在历史记录里，点一下直接再搜。",
        ),
    ),
    ManualSection(
        title = "常见问题",
        points = listOf(
            "搜不到片：先看站源页当前规则切对了没，再看订阅地址有没有过期。",
            "磁力播不了：检查转播服务开了没（127.0.0.1:17890）。",
            "首页空白：大概率是订阅地址挂了，换个能用的重新加。",
            "卡在「初始化」：等两秒还不行就退掉 App 重进。",
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用手册", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(MANUAL_SECTIONS) { index, section ->
                ManualSectionCard(index = index + 1, section = section)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ManualSectionCard(index: Int, section: ManualSection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            section.points.forEach { point ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = point,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
