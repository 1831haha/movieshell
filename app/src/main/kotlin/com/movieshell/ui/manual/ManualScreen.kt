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
        title = "侠影是什么",
        points = listOf(
            "侠影是一个本地影视聚合壳：把多个站源、多种规则聚到一个 App 里搜片、看片。",
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
            "网页源：✅ 可用，站源页「网页源」tab 点「添加」录入网站，点击激活，内置嗅探抓 m3u8 / mp4 直链播放。",
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
        title = "各类源使用指南",
        points = listOf(
            "CMS 采集站（type 0/1）：就是苹果 CMS 那套接口。站源页「接口站源」tab 点「添加订阅」粘贴 URL；添加后点订阅条目右边的展开箭头→「解析站点」，点某个站点设为当前源。",
            "drpy JS 源：.js 规则文件。放 xiaying/js/（文件管理器直接拷，或站源页 JS tab 点「导入」）；JS tab 里点文件名就是激活当前源，长按有预览/编辑/分享等菜单。",
            "PY 源：.py 规则文件（hipy 风格）。放 xiaying/py/；PY tab 点击激活。PY 引擎已内置（Chaquopy），第一次用初始化等 1~2 秒。",
            "type=4 PHP 站：订阅填 http://127.0.0.1:19978/config.php 这类 PHP 服务地址；手机上 19978 的 PHP 服务要开着（别人服务器上的远端地址也行）。小说/漫画站不支持。",
            "Java spider（csp_ 站）：订阅自带。jar 自动下载到 xiaying/jar/，站源页这类站打「spider」徽标，直接点设为当前源。",
            "网页源：放 xiaying/web/ 的网站配置（.json）。网页源 tab 点击激活为当前源；播放靠内置浏览器嗅探抓直链。长按菜单里「打开嗅探浏览器」可手动进网页点播。",
            "磁力：本质是搜出 magnet 链接。btsow 这类 PY/drpy 规则 + 本机 magnet-stream 转播服务（127.0.0.1:17890，服务页启动），点播放自动转成边下边播。",
        ),
    ),
    ManualSection(
        title = "添加入口总览",
        points = listOf(
            "首页搜索框上方：一行站源入口，点开 BottomSheet 切换当前源（本地规则+已解析订阅站点，选完首页立即重载）。",
            "站源页：顶部五个 tab——「接口站源」管订阅（添加/解析/删），「JS / PY / PHP / 网页源」各管对应文件（导入/添加/激活/长按菜单）。",
            "订阅添加：站源页「接口站源」tab→「添加订阅」粘贴 URL；本地有 TVBox JSON 文件的？目前只支持 URL 订阅。",
            "规则文件添加：三种姿势——站源页对应 tab 点「导入」；文件管理器直接拷到 xiaying/js|py|php/；手机插电脑 U 盘模式拷。",
            "网页源添加：站源页「网页源」tab→「添加」，填名称和网址（http 开头）。",
            "删除：在对应 tab 长按文件→「删除文件」二次确认；订阅在「接口站源」tab 删。",
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
        title = "文件存放路径总表",
        points = listOf(
            "用户文件全在公开目录 /storage/emulated/0/xiaying/（不开权限则退到 App 私有 filesDir/xiaying/，App 照常用）。",
            "js/：drpy 规则（.js）；py/：PY 规则（.py）；php/：PHP 规则（.php，只能保存不能执行）；web/：网页源配置（.json）。",
            "jar/：订阅的 spider jar 缓存（自动下载，手别删，删了下次用会重新下）；backup/：备份文件默认导出位置；log/：运行日志导出位置。",
            "站源页长按菜单的「置顶到首」顺序存在 App 设置里（prefs sources_pin），删文件会同步清理。",
            "从旧版升级：第一次启动自动把旧私有目录的规则搬到公开目录，搬完才删旧文件，丢不了。",
            "这些是 App 自己的运行数据，留在私有 filesDir（看不到也正常，手别改）：api_sources.json（接口站源列表）、sub_sites.json（订阅站点缓存）、搜索历史、收藏、主题和开关设置。",
            "Android 11 以上第一次启动会请你开「所有文件访问权限」，不开也能用，只是文件存在私有目录。",
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
            "v0.8.0 已实现：Java spider（csp_ 站）运行时——按订阅自动下载 spider jar（缓存在 xiaying/jar/），DexClassLoader 动态加载执行，首页/分类/搜索/详情/播放全链路可用（见「Java spider（csp_ 站）」一节）。",
            "v0.9.0 已实现：站源页重做——顶部数据来源 Tab（接口站源/JS/PY/PHP/网页源）、双列文件网格（单击激活、长按操作菜单：预览/编辑/分享/信息/置顶/删除）、文件预览器（只读等宽+关键词高亮+200KB 保护）、FileProvider 对外分享/编辑、使用手册大完善（各类源使用指南/添加入口总览/存放路径总表）。",
            "v0.10.0 已实现：嗅探引擎增强——无后缀流探测（HEAD 判 Content-Type）、捕获扩展（.mpd/.aac/.rtmp）、iframe 剥壳、命中停载、混合内容+第三方 Cookie、嗅探页浏览器式头部（一键嗅探测试）、网页源导入 .html；手册新增「已知限制」「版本更新」两节。",
            "下一步（规划中）：订阅云同步、播放器投屏。",
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
        title = "Java spider（csp_ 站）",
        points = listOf(
            "csp_ 开头（如 csp_Mp4Mov）的站，规则不是 JS/PY 文件，而是一个跑在订阅自带 jar 包里的 Java 类（Java spider），v0.8.0 起侠影原生支持。",
            "原理：添加订阅时自动记下配置里的 spider 字段（如 ./tvkj.jar）；第一次用某个 csp_ 站时自动下载 jar 到 /storage/emulated/0/xiaying/jar/，用 DexClassLoader 动态加载执行。",
            "站源页这类站点打「spider」徽标，可直接设为当前源，首页、分类、搜索、详情、播放全流程可用；单个站加载失败会如实 Toast，不影响其他站。",
            "jar 按订阅缓存，多站共用一份，不重复下载；云盘类 spider（如夸克/UC/迅雷盘）部分功能依赖其账号体系，能否播以实际为准。",
        ),
    ),
    ManualSection(
        title = "已知限制",
        points = listOf(
            "MSE/blob 直链未处理：视频走 MediaSource/Blob 播的站，嗅探抓不到直链，只能手动在页面里点播放看。",
            "cheerio 类规则不支持：drpy 一级为 jsp:/json:/html 解析串（需 pdfa/pdfh/cheerio）的规则跑不起来。",
            "Cloudflare 人机验证：遇到 CF 验证页需在嗅探页里手动过验证，过了之后再抓直链。",
            "rtmp 流需外部播放：抓到 rtmp 地址会提示，不会自动播（App 内没接 rtmp 播放器）。",
            "m3u8 去广告：v0.18.0 起播放器内置去广告（过滤播放列表中的广告分片）；嗅探页拿到的是源站原始播放列表，不经过滤。",
            "网页源搜索/详情不走引擎：网页源只支持打开嗅探页手动找直链，不参与聚合搜索。",
        ),
    ),
    ManualSection(
        title = "版本更新",
        points = listOf(
            "v0.23.0 新功能：插件协议对齐 DsPlayer——market.json 切兼容 schema（author/size/icon/tags/changelog/platforms/category/minApp/abis 全字段）；插件详情页（图标/作者/版本/大小/标签/平台/更新说明）；分类过滤（全部/环境/应用）；不支持的 type（apk/import）默认隐藏，设置里可开"显示不兼容插件"仅查看；minApp 门槛（版本不够安装按钮置灰）；设置→插件可增删多市场源（可手动添加 DsPlayer 官方市场跨装 source/server/live）。",
            "v0.23.0 安装升级：MD5 强制校验后读包内 plugin.json 与市场条目做 id/version 一致性校验；source 插件 engine 缺省时按包内目录嗅探（兼容 DsPlayer 源包）；live 裸 JSON 存 xiaying/live/<id>.json（直播页 v0.24 做）。",
            "v0.22.0 新功能：插件体系——官方插件仓库（xiaying-plugins）+ App 内插件市场页（站源页顶部进入）：浏览/搜索/一键安装/版本更新/卸载；安装时 MD5 强制校验；source 插件（py/js/php）解压到对应规则目录即用，server 插件下载到 xiaying/plugins/<id>/（Node.js 服务需外部运行）。设置→插件可改市场地址。",
            "v0.22.0 引擎：PY 读方法（首页/分类/详情/搜索）并发执行，切源/切分类时取消旧在途请求（对标 DsPlayer 0.9.9 源忙根除；Chaquopy 进程内 Python，网络 I/O 期间释放 GIL 天然重叠）。",
            "v0.21.0 新功能：播放失败自动换内核——Media3 失败静默切 libmpv 重试（反之亦然），同地址只换一次，换完还不行才走线路重试。",
            "v0.21.0 新功能：字幕支持 .ass（Media3 内建解析，libmpv 原生），样式面板可调大小/颜色/描边/位置，时间偏移 ±10s（双引擎通用）。",
            "v0.21.0 新功能：定时关闭——15/30/60/90 分钟或播完本集停止，播放器顶部栏进入，倒计时显示在顶部栏。",
            "v0.21.0 新功能：播放器截图——顶部栏一键截当前画面，存 xiaying/Screenshots（Media3 系统抽帧，libmpv 原生截图）。",
            "v0.21.0 新功能：详情页影片信息一键复制（标题/年份/主演/简介），封面点开放大看图。",
            "v0.21.0 新功能：播放器长按 2x 快放——按住不动进 2x，松手恢复原倍速。",
            "v0.21.0 防盗链：嗅探命中补传 Cookie；CMS 直链默认带站点 Referer；字幕下载带播放请求头，全链路透传到播放器。",
            "v0.20.0 新功能：双播放器内核——Media3（默认）与 libmpv，播放器顶部栏一键切换，设置→播放里也能切，选择会被记住；libmpv 经 Maven 预编译包接入（mpv v0.40，软硬解自动），专治 Media3 播不了的冷门格式。",
            "v0.20.0 已知限制：libmpv 为可选引擎，默认仍走 Media3，需手动切换；切换内核后从当前进度重播（有短暂重载）；libmpv 模式底部控制条为极简版（播放/暂停+进度），顶部栏与手势功能不变。",
            "v0.19.0 新功能：播放器补全——FLV 直播/点播、播放器内线路+选集切换、播放失败自动换线路重试、外挂字幕（.srt/.vtt，按剧记忆）、画中画（按钮+按 Home 自动进小窗）、设置页可添加自定义解析接口（优先于订阅自带线路）。",
            "v0.19.0 重构：详情页播放决策抽成公共 resolvePlayUrl，播放器内切线路/自动重试复用同一链路；解析线路合并走 ParseStore 单一入口。",
            "v0.10.0 新功能：嗅探引擎增强——无后缀流探测（HEAD 看 Content-Type 判定 m3u8/mp4/mpd/aac）、捕获扩展（.mpd/.aac/.rtmp）、iframe 剥壳（页面里的 iframe 逐个探测直链）、命中后停载省流量、混合内容放行+第三方 Cookie、嗅探页浏览器式头部（后退/前进/刷新+当前 URL+一键嗅探测试）、网页源支持导入 .html 文件。",
            "v0.10.0 修复：嗅探判定逻辑收敛到 SniffEngine（之前散在 UI 和解析链路两处）。",
            "v0.10.0 已知限制：见「已知限制」一节（MSE/blob、cheerio、人机验证、rtmp、m3u8 去广告）。",
            "以后每版都会在这里写 changelog：新功能 / 修复 / 已知限制三段。",
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
