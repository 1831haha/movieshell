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
        title = "五个页面",
        points = listOf(
            "首页：按当前激活的规则展示推荐和分类，下拉可以刷新。",
            "搜索：输入片名一次搜所有启用的源，结果按源分组；搜索框为空时显示历史记录。",
            "站源：管理所有订阅和规则文件的地方，也是切换当前规则的入口。",
            "收藏：追剧收藏夹，详情页点收藏按钮就会出现在这里。",
            "设置：默认播放器、清理缓存、备份与恢复、使用手册、关于都在这里。",
        ),
    ),
    ManualSection(
        title = "支持的源类型一览",
        points = listOf(
            "TVBox JSON 订阅：CMS type0 / type1 接口，✅ 可用，点「添加订阅」粘贴地址。",
            "TVBox 完整订阅配置（含 sites 数组，如带几十个站的大包）：✅ v0.5.1 起支持——站源页点订阅条目展开「解析站点」，CMS / drpy / PY 站可用，csp_ Java spider 站不支持。",
            "csp_ 开头的 Java spider 源：❌ 不支持，详见下面「为什么 csp_ 源播不了」一节。",
            "JS drpy 规则：.js 文件，WebView 真执行，✅ 可用。",
            "PY 规则：.py 文件（hipy 风格），Chaquopy 内置 Python 直接执行，✅ 可用。",
            "网页源：✅ 可用，站源页「网页站源」添加网站，内置嗅探抓 m3u8 / mp4 直链播放。",
            "磁力 magnet：✅ 可用，需要本机装好转播服务并启动。",
            "PHP 规则：❌ 暂不支持执行——手机上没有 PHP 解释器，只能导入保存，点它会如实提示。",
        ),
    ),
    ManualSection(
        title = "每种源怎么加",
        points = listOf(
            "接口站源：站源页点「添加订阅」，粘贴 TVBox JSON 订阅地址，确定就行；增删和开关会自动保存。",
            "完整订阅配置（含 sites 数组）：添加后点订阅条目右边的展开箭头，点「解析站点」——App 会下载并展开全部站点，站名后面带类型徽标（CMS / drpy / PY / 不支持）；点某个可用站点就是「设为点播页当前源」，首页会切到该站的推荐和分类；搜索页会自动逐站搜（不支持的站自动跳过）。",
            "纯 CMS 地址（直接返回采集数据的接口）：不用解析，按老样子当单个 CMS 源用。",
            "JS 规则：站源页「JS文件夹」点「导入」，选 .js 文件。",
            "PY 规则：站源页「PY文件夹」点「导入」，选 .py 文件。",
            "网页源：站源页「网页站源」点「添加」，填网站名称和地址（http 开头），点条目进入网页嗅探。",
            "PHP：对应分组点「导入」选文件；目前只能保存不能执行。",
            "本地路径（一般不用记）：/data/data/com.movieshell.debug/files/ 下 drpy/、py/、php/、web/ 各放一类；直接用导入/添加按钮最省事，有 root 的话可以用 MT 管理器进 data/data 查看。",
            "导入后如果该分组还没有激活的规则，新文件会自动设为当前，不用再点一遍。",
        ),
    ),
    ManualSection(
        title = "播放与磁力",
        points = listOf(
            "magnet: 开头的链接点播放，会自动探测本机转播服务 127.0.0.1:17890（magnet-stream 插件）。",
            "服务没启动会弹提示：先去把转播服务打开，再回来点播放。",
            "内置 ExoPlayer 播放器，m3u8 / mp4 等常见格式直接播。",
            "多线路：如果详情页有多条播放线路（如线路1/线路2），播放按钮上方会出现线路 Tab，切换后选集列表跟着变。",
            "追剧收藏：详情页顶部点收藏按钮，收藏夹在底栏「收藏」Tab 里，点进去回到详情接着看。",
            "倍速：播放器顶部栏点倍速按钮，0.5x ~ 2x 循环切换。",
            "手势：播放画面上左右滑快进/快退 10 秒，左半屏上下滑调亮度，右半屏上下滑调音量。",
            "网页嗅探：进网页后如果 20 秒没抓到直链会提示，可继续手动浏览点播；抓到的直链会自动带上来源和 UA，防盗链严的站也能播；部分站点的视频请求抓不到属于正常。",
            "播放历史自动记录进度，下次点开这集接着看；搜过的词也会留在搜索历史里。",
        ),
    ),
    ManualSection(
        title = "备份与恢复",
        points = listOf(
            "设置页「备份与恢复」：一键把接口站源列表和各分组激活状态导出成 JSON 文件。",
            "换手机 / 重装后，点「从文件恢复」选之前导出的文件，配置就回来了。",
            "备份文件里不含规则文件本身，只含站源地址和激活状态——规则文件请另外保存好。",
            "恢复时如果某条规则文件在新手机上不存在，会自动跳过，不会报错卡住。",
        ),
    ),
    ManualSection(
        title = "开发进度",
        points = listOf(
            "v0.4 已实现：网页源（WebView 嗅探直链）、订阅备份恢复、播放倍速与手势、接口站源持久化。",
            "v0.4.1：AI 功能已砍掉（整个 AI 源类型移除）。",
            "v0.5 已实现：详情页多线路切换、聚合搜索（一次搜所有源）+ 搜索历史、追剧收藏、网页嗅探透传 Referer/UA。",
            "v0.5.1 已实现：完整 TVBox 订阅配置解析（sites 展开、drpy 规则相对路径下载到本地、csp_ 站标不支持）、点播页可设订阅当前源、type=3 按 api 后缀正确分流（修了之前全判成 PY 源的 bug）。",
            "PHP 规则执行：评估结论是建议砍掉（手机端无 PHP 解释器是硬约束），等最终拍板；拍板前 App 内如实提示暂不支持。",
            "下一步（规划中）：订阅云同步、播放器投屏。",
            "每个版本都会发安装包实测，版本号在设置页「关于」里看。",
        ),
    ),
    ManualSection(
        title = "为什么 csp_ Java spider 源播不了",
        points = listOf(
            "csp_ 开头（如 csp_Douban）的站，规则不是 JS/PY 文件，而是一个跑在订阅自带 tvkj.jar 里的 Java 类（Java spider）。",
            "TVBox 系壳子播它，靠的是把 jar 动态加载进自己进程再反射调用 spider 类——壳子得专门写一套 DexClassLoader 加载机制，还要跟着 jar 的版本修兼容。",
            "MovieShell 现在没有这套 Java spider 运行时，所以这类站点统一标「不支持」，点它会如实告诉你原因，不会进引擎瞎试浪费时间。",
            "想看的站如果是 csp_ 的，可以去找找同名的 drpy（.js）/ PY（.py）/ CMS 版本，功能一般是一样的。",
        ),
    ),
    ManualSection(
        title = "常见问题",
        points = listOf(
            "搜不到片：先去站源页看看订阅和规则开没开；现在搜索是一次搜所有源，某个源挂了会自动跳过，不影响别的源出结果。",
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
