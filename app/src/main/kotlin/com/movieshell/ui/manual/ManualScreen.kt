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
            "TVBox 完整订阅配置（含 sites 数组，如带几十个站的大包）：✅ v0.5.1 起支持——站源页点订阅条目展开「解析站点」，CMS / drpy / PY / type=4 PHP 站可用，csp_ Java spider 站不支持。",
            "csp_ 开头的 Java spider 源：❌ 不支持，详见下面「为什么 csp_ 源播不了」一节。",
            "JS drpy 规则：.js 文件，WebView 真执行，✅ 可用。",
            "PY 规则：.py 文件（hipy 风格），Chaquopy 内置 Python 直接执行，✅ 可用。",
            "网页源：✅ 可用，站源页「网页站源」添加网站，内置嗅探抓 m3u8 / mp4 直链播放。",
            "磁力 magnet：✅ 可用，需要本机装好转播服务并启动。",
            "type=4 PHP 站：✅ v0.6 起支持——api 是 PHP 脚本的 HTTP 地址，详见下面「type=4 PHP 站」一节。",
            "本地 PHP 规则文件（php: 分组导入的 .php）：❌ 仍不支持执行——手机上没有 PHP 解释器，只能导入保存。",
        ),
    ),
    ManualSection(
        title = "type=4 PHP 站（v0.6 新增）",
        points = listOf(
            "是什么：订阅里 type=4 的站，它的 api 不是采集接口，而是一个 PHP 脚本的 HTTP 地址（如 http://127.0.0.1:19978/人人影视 ᵈᶻ.php）。",
            "原理：PHP 在服务里执行（别人服务器上，或自己手机上用 php -S 起本地服务），App 只发 HTTP 请求、解析返回的 JSON——手机上不需要装 PHP 解释器。",
            "前置条件：PHP 服务必须先跑起来。本地：在手机上起 php -S（如 127.0.0.1:19978，注意别和已占用的 19978/17890/8080/8888 这些端口撞）；远端：直接用服务地址。",
            "怎么加：站源页「添加订阅」粘贴吐出 type=4 站点的 config.php 地址，「解析站点」后这些站会标 PHP 徽标；点一个「设为点播页当前源」，首页切到该站的推荐和分类，搜索页也会逐站搜。",
            "请求映射：首页 GET {api} 取分类+推荐；分类 ?ac=detail&t=&pg=；搜索 ?wd=；详情 ?ac=detail&ids=；播放 ?ac=play&flag=&id=。",
            "api 里带中文/空格（如「人人影视 ᵈᶻ.php」）App 会自动编码再请求；播放时服务返回的 Referer/UA 会自动带上，防盗链的直链也能播。",
            "返回 parse=1 的站（需走解析线路）暂按直链播，后续版本接解析链路。",
            "小说/漫画站：订阅里「类型」标小说/漫画的站统一标「不支持」，App 只做视频。",
        ),
    ),
    ManualSection(
        title = "每种源怎么加",
        points = listOf(
            "接口站源：站源页点「添加订阅」，粘贴 TVBox JSON 订阅地址，确定就行；增删和开关会自动保存。",
            "完整订阅配置（含 sites 数组）：添加后点订阅条目右边的展开箭头，点「解析站点」——App 会下载并展开全部站点，站名后面带类型徽标（CMS / drpy / PY / PHP / 不支持）；点某个可用站点就是「设为点播页当前源」，首页会切到该站的推荐和分类；搜索页会自动逐站搜（不支持的站自动跳过）。",
            "纯 CMS 地址（直接返回采集数据的接口）：不用解析，按老样子当单个 CMS 源用。",
            "JS 规则：站源页「JS文件夹」点「导入」，选 .js 文件。",
            "PY 规则：站源页「PY文件夹」点「导入」，选 .py 文件。",
            "网页源：站源页「网页站源」点「添加」，填网站名称和地址（http 开头），点条目进入网页嗅探。",
            "PHP：type=4 的站走「接口站源」订阅路线（见「type=4 PHP 站」一节）；php: 分组导入的本地 .php 规则文件目前只能保存不能执行。",
            "文件存放路径（v0.7.2 起）：规则和备份都在 /storage/emulated/0/xiaying/ 下，文件管理器直接能看到，详见「文件存放路径」一节。",
            "导入后如果该分组还没有激活的规则，新文件会自动设为当前，不用再点一遍。",
        ),
    ),
    ManualSection(
        title = "首页切换站源",
        points = listOf(
            "首页搜索框上方有一行站源入口，显示当前源名称（订阅源名 / 本地·规则名 / 演示数据）。",
            "点它弹出底部菜单：上半是本地 JS / PY 规则（每行标「本地」徽标），下半是已解析订阅的站点（按订阅名分组）。",
            "订阅站点带类型徽标：CMS / drpy / PY / PHP / 不支持；「不支持」的行点它会告诉你原因（如 Java spider、小/漫画站）。",
            "选中后自动关菜单，首页立即重载该源的推荐和分类——不用再去站源页切。",
        ),
    ),
    ManualSection(
        title = "主题设置（外观）",
        points = listOf(
            "设置页「外观」→「主题」：跟随系统 / 深色 / 浅色三档，切换立即全 App 生效并记住。",
            "「跟随系统」用的是手机系统的深色/浅色设置，系统切了 App 跟着切。",
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
            "设置页「备份与恢复」：一键把接口站源列表和各分组激活状态导出成 JSON 文件，默认存到 /storage/emulated/0/xiaying/backup/，文件名带时间戳。",
            "换手机 / 重装后，点「从文件恢复」选之前导出的文件，配置就回来了。",
            "备份文件里不含规则文件本身，只含站源地址和激活状态——规则文件请另外保存好。",
            "恢复时如果某条规则文件在新手机上不存在，会自动跳过，不会报错卡住。",
        ),
    ),
    ManualSection(
        title = "文件存放路径（v0.7.2 起）",
        points = listOf(
            "用户文件全在公开目录 /storage/emulated/0/xiaying/，文件管理器直接能看到、能拷、能改，不用 root。",
            "js/：drpy 规则（.js）；py/：PY 规则（.py）；php/：PHP 规则（.php）；web/：网页源（.json）。",
            "backup/：备份文件默认导出位置；log/：运行日志导出位置；jar/：预留，以后放订阅的 spider 包。",
            "从旧版升级：第一次启动会自动把原来私有目录里的规则搬过来，搬完才删旧文件，丢不了。",
            "这些是 App 自己的运行数据，留在私有目录（看不到也正常）：接口站源列表、订阅站点缓存、搜索历史、收藏、主题和开关设置。",
            "Android 11 以上的手机第一次启动会请你开「所有文件访问权限」，不开也能用，只是文件会存在私有目录。",
        ),
    ),
    ManualSection(
        title = "运行日志（v0.7.2 新增）",
        points = listOf(
            "设置页「调试」里打开「运行日志悬浮窗」，屏幕上会出现一个虚化小泡，可以拖到任意位置。",
            "点开小泡：最近 2000 条运行日志倒序展示，可按 D/I/W/E 级别过滤，订阅下载、解析线路、播放错误这些关键步骤都有记录。",
            "「导出日志」：写成 txt 存到 /storage/emulated/0/xiaying/log/，文件名带时间戳；「复制日志」：整份进剪贴板，发给人看问题时用。",
            "App 崩了也会记一条，未捕获的异常跑不掉。日志只存在本机，不上传。",
            "不用的时候点「关闭悬浮窗」，或回设置页关掉开关。",
        ),
    ),
    ManualSection(
        title = "开发进度",
        points = listOf(
            "v0.4 已实现：网页源（WebView 嗅探直链）、订阅备份恢复、播放倍速与手势、接口站源持久化。",
            "v0.4.1：AI 功能已砍掉（整个 AI 源类型移除）。",
            "v0.5 已实现：详情页多线路切换、聚合搜索（一次搜所有源）+ 搜索历史、追剧收藏、网页嗅探透传 Referer/UA。",
            "v0.5.1 已实现：完整 TVBox 订阅配置解析（sites 展开、drpy 规则相对路径下载到本地、csp_ 站标不支持）、点播页可设订阅当前源、type=3 按 api 后缀正确分流（修了之前全判成 PY 源的 bug）。",
            "v0.6.0 已实现：type=4 PHP 站支持（Type4Api：首页/分类/搜索/详情/播放全链路，api 含中文/空格自动编码，播放 header 透传给播放器；小说/漫画站标不支持）。",
            "v0.7.0 已实现：parses 解析链路（parse=1 自动走订阅解析线路，type1 JSON 取直链/type0 WebView 嗅探，失败自动换下一条）、Service Worker 嗅探补齐（SW 上下文的 .m3u8/.mp4 也能抓到）、drpy 分类筛选生效（filter_url 模板渲染，cheerio 类规则仍不支持）。",
            "v0.7.1 已实现：首页搜索框上方站源切换入口（点开 BottomSheet 选源即切）、主题切换真生效（浅色配色+跟随系统）、备份/恢复并为可展开入口。",
            "v0.7.2 已实现：文件全迁到 /storage/emulated/0/xiaying/ 公开目录（见「文件存放路径」）、运行日志悬浮窗（见「运行日志」一节）。",
            "下一步（规划中）：订阅云同步、播放器投屏。",
            "每个版本都会发安装包实测，版本号在设置页「关于」里看。",
        ),
    ),
    ManualSection(
        title = "解析链路（v0.7 新增）",
        points = listOf(
            "parses 是什么：TVBox 订阅 JSON 顶层的 parses 数组，是「解析线路」列表。有些站（type=4 的 PHP 站返回 parse=1）给出的播放地址不是直链，而是要再过一遍解析线路才能拿到真正的 .m3u8/.mp4。",
            "type0 / type1 区别：type=1 是 JSON 接口——App 直接请求「线路地址+视频地址」，从返回 JSON 的 url 字段拿直链，快且稳；type=0 是网页嗅探——App 用内置浏览器打开解析页，从网络请求里抓第一条 .m3u8/.mp4，慢一些（单条最多等 20 秒）但能对付 JS 渲染的解析页。",
            "怎么用：全自动。点播放时若站点标了 parse=1，App 按订阅里的 parses 顺序一条一条试，第一条出直链就播，不用手动选线路。v1 不提供手动切换线路。",
            "失败会怎样：所有线路试完还没拿到直链，会弹 Toast「解析失败」。常见原因：订阅本身没带 parses、解析线路挂了。换个站源或换集试试。",
        ),
    ),
    ManualSection(
        title = "SW 嗅探说明（v0.7 新增）",
        points = listOf(
            "网页源嗅探新增 Service Worker 补齐：原来只拦 WebView 主线程的网络请求，部分站点把视频地址的请求走在 Service Worker 上下文里，原嗅探抓不到；现在这类请求也会被拦截判定，抓到 .m3u8/.mp4 直链即走与原来相同的命中流程（首条生效、停载、交播放器、透传 Referer/UA）。",
            "生效条件：手机 WebView 版本支持 SW 拦截特性（较新的 Android System WebView 基本都支持）；不支持的机器静默沿用原嗅探链路，不影响使用，无需手动开启。",
        ),
    ),
    ManualSection(
        title = "drpy 分类筛选（v0.7 新增）",
        points = listOf(
            "filterable 标记的 drpy 规则，分类筛选（如年份/类型/地区/排序）已生效：筛选定义取自规则的 filter 分组（含 gzip 压缩写法自动解开），filter_url 里的 {{fl.键名}} 按所选值渲染后拼进分类请求，不选时按规则默认值渲染。",
            "边界：cheerio 类规则（一级为 jsp:/json:/html 解析串，需 pdfa/pdfh/cheerio）的筛选仍不支持——垫片没有 HTML 解析能力，这类规则的分类列表本身也跑不起来。",
            "筛选器 UI 尚未接入，当前按默认值出结果。",
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
