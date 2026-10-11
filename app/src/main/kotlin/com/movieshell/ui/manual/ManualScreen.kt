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
        title = "MovieShell 是什么",
        points = listOf(
            "MovieShell 是一个本地影视聚合壳：把多个站源、多种规则聚到一个 App 里搜片、看片。",
            "多源聚合：一次装多个站源，搜片、看推荐不用在几个 App 之间来回切。",
            "本地规则：JS / PY 规则文件存在手机本地，随身带，不依赖在线订阅也能用。",
            "磁力转播：magnet 链接自动走本机转播服务，边下边播。",
            "播放历史：看过的进度自动记住，下次点开断点接着看。",
        ),
    ),
    ManualSection(
        title = "四个页面",
        points = listOf(
            "首页：按当前激活的规则展示推荐和分类，下拉可以刷新。",
            "搜索：输入片名搜片，搜的是当前激活的那条规则（或站源）。",
            "站源：管理所有订阅和规则文件的地方，也是切换当前规则的入口。",
            "设置：默认播放器、清理缓存、使用手册、关于都在这里。",
        ),
    ),
    ManualSection(
        title = "支持的源类型一览",
        points = listOf(
            "TVBox JSON 订阅：CMS type0 / type1 接口，✅ 可用，点「添加订阅」粘贴地址。",
            "JS drpy 规则：.js 文件，WebView 真执行，✅ 可用。",
            "PY 规则：.py 文件（hipy 风格），Chaquopy 内置 Python 直接执行，✅ 可用。",
            "磁力 magnet：✅ 可用，需要本机装好转播服务并启动。",
            "AI 规则：🚧 开发中——可以导入保存，暂时还不能执行。",
            "PHP 规则：🚧 开发中——可以导入保存，暂时还不能执行。",
            "网页源：🚧 规划中，还没排期。",
        ),
    ),
    ManualSection(
        title = "每种源怎么加",
        points = listOf(
            "接口站源：站源页点「添加订阅」，粘贴 TVBox JSON 订阅地址，确定就行。",
            "JS 规则：站源页「JS文件夹」点「导入」，选 .js 文件。",
            "PY 规则：站源页「PY文件夹」点「导入」，选 .py 文件。",
            "AI / PHP：对应分组点「导入」选文件，能存但暂不能执行，引擎做好了自动生效。",
            "本地路径（一般不用记）：/data/data/com.movieshell.debug/files/drpy/ 放 JS，files/py/ 放 PY，files/ai/、files/php/ 同理；直接用导入按钮最省事，有 root 的话可以用 MT 管理器进 data/data 查看。",
            "导入后如果该分组还没有激活的规则，新文件会自动设为当前，不用再点一遍。",
        ),
    ),
    ManualSection(
        title = "播放与磁力",
        points = listOf(
            "magnet: 开头的链接点播放，会自动探测本机转播服务 127.0.0.1:17890（magnet-stream 插件）。",
            "服务没启动会弹提示：先去把转播服务打开，再回来点播放。",
            "内置 ExoPlayer 播放器，m3u8 / mp4 等常见格式直接播。",
            "播放历史自动记录进度，下次点开这集接着看；搜过的词也会留在搜索历史里。",
        ),
    ),
    ManualSection(
        title = "开发进度",
        points = listOf(
            "v0.3 已实现：drpy JS 规则真执行（WebView + 垫片）、Chaquopy 内置 PY 引擎、磁力转播接入、站源文件导入、使用手册。",
            "下一步（规划中）：AI / PHP 规则执行引擎、网页源支持、订阅备份与恢复。",
            "每个版本都会发安装包实测，版本号在设置页「关于」里看。",
        ),
    ),
    ManualSection(
        title = "常见问题",
        points = listOf(
            "搜不到片：先去站源页看看当前规则切对了没，换条规则再试；订阅地址也可能过期了。",
            "磁力播不了：检查转播服务开了没（127.0.0.1:17890），冷门资源多等一会儿，别频繁重播。",
            "安装包解析失败：先把旧版卸载再装——云构建每版的测试签名都不一样，不能直接覆盖安装。",
            "首页空白：大概率是订阅地址挂了，删掉换个能用的重新加。",
            "卡在初始化：等两秒还不行就退掉 App 重进；PY 规则第一次用要初始化引擎，等 1~2 秒正常。",
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
