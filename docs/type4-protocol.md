# type=4 站源对接协议（PHP 站）

> 来源：用户提供的 7 个 `.php` 规则文件实测分析 ＋ hjdhnx/drpy-node `spider/php/readme.md`
> 与《PHP Spider 开发与维护指南 (DZ 风格)》公开文档交叉验证。
> 状态：方法签名与返回结构为**实测确认**；`run()` 的 HTTP 调度参数为**高置信度推断**（见 §5），
> 需对照 `lib/spider.php` 源码或用户实机（127.0.0.1:19978）抓包做最终确认。

## 1. 一句话结论

- `type: 4` 的站，其 `api` 是一个 **PHP 脚本的 HTTP 地址**（如 `http://127.0.0.1:19978/人人影视 ᵈᶻ.php`）。
- PHP 在远端或本机 `php -S` 服务里执行，**壳子只发 HTTP 请求、解析 JSON**，不需要在 App 里跑 PHP。
- 7 个文件中存在**两种**写法：
  - **模式A（BaseSpider 框架）**：`require lib/spider.php`，`class Xxx extends BaseSpider`，
    实现固定方法签名，结尾 `(new Xxx())->run()`，由框架的 `run()` 做 HTTP 调度。（4 个文件）
  - **模式B（独立文件）**：不依赖框架，文件顶部自己读 `$_GET` 做路由分发。（3 个文件）

## 2. 站点注册格式（config.php 吐出的 sites 条目）

```json
{
    "key": "php_人人影视 ᵈᶻ",
    "name": "人人影视 ᵈᶻ(PHP)",
    "type": 4,
    "api": "http://127.0.0.1:19978/人人影视 ᵈᶻ.php",
    "searchable": 1,
    "quickSearch": 1,
    "changeable": 0,
    "类型": "小说"          // 可选；小说/漫画站用"类型"字段标识（勿用 categories）
}
```

注意：`api` URL 含空格与 Unicode（`ᵈᶻ`），客户端必须按 URL 编码规范处理（用 HttpUrl 解析，
不要裸字符串拼接）。

## 3. 模式A：BaseSpider 框架（4 个文件已验证）

涉及文件：`________2_lgk4.php`（B站）、`________3_7gpx.php`（哇哇影视）、
`B_____6_g18o.php`（B站）、`_______7_s02k.php`（YiYiShi/一一影视）。

### 3.1 文件骨架

```php
require_once __DIR__ . '/spider.php';        // 或 __DIR__ . '/lib/spider.php'
class Spider extends BaseSpider {            // 类名不固定（见过 Spider、YiYiShi）
    public function init($extend = '') { ... }
    public function homeContent($filter = []) { ... }
    public function homeVideoContent() { ... }
    public function categoryContent($tid, $pg = 1, $filter = [], $extend = []) { ... }
    public function searchContent($key, $quick = false, $pg = 1) { ... }
    public function detailContent($ids) { ... }
    public function playerContent($flag, $id, $vipFlags = []) { ... }  // 或 playContent
    // 基类另提供：fetch($url,$options,$headers)、pageResult(...)、pdfa/pdfh/pd(...)
}
(new Spider())->run();
```

### 3.2 方法签名与返回结构（实测确认）

| 方法 | 签名 | 返回 |
|---|---|---|
| `init` | `init($extend = '')` | 无（初始化 headers/cookie；`$extend` 来自站点配置的 ext） |
| `homeContent` | `homeContent($filter = [])` | `{"class":[{"type_id","type_name"}...], "filters":{tid:[{"key","name","value":[{"n","v"}]}]}, "list":[...]?}`（`list` 可选，部分源在 homeContent 里直接带推荐列表） |
| `homeVideoContent` | `homeVideoContent()` | `{"list":[vod...]}`（首页推荐） |
| `categoryContent` | `categoryContent($tid, $pg=1, $filter=[], $extend=[])` | `pageResult` 结构（见下） |
| `searchContent` | `searchContent($key, $quick=false, $pg=1)` | `pageResult` 结构 |
| `detailContent` | `detailContent($ids)`（`$ids` 为数组，取 `[0]`） | `{"list":[vod_detail]}` |
| `playerContent` | `playerContent($flag, $id, $vipFlags=[])`；个别源写作 `playContent`（`________2_lgk4.php`），框架疑似二者兼容（`method_exists` 探测，**待确认**） | `{"parse":0\|1, "url":"...", "header":{...}, ...}` |

### 3.3 通用字段结构（实测确认）

**vod（列表项）**：`vod_id`（字符串）、`vod_name`、`vod_pic`、`vod_remarks`
（部分源另带 `vod_content`、`vod_year` 等；有 `filters` 的源，`list` 项建议带 `vod_year/vod_actor/vod_director`
以保轮播图正常，见 drpy-node 文档）。

**pageResult**（框架辅助方法，实测三种调用形态）：
`pageResult($list, $page, $total, $limit = 20)` →
`{"list":[...], "page":int, "pagecount":int, "limit":int, "total":int}`。
`$total=0` 时的 `pagecount` 取值未知（哇哇影视传 0），客户端应做容错（`pagecount<1` 按 1 处理）。

**vod_detail**：`vod_id/vod_name/vod_pic/vod_remarks/vod_content` ＋
`vod_play_from`（线路名 `$$$` 分隔）＋ `vod_play_url`（线路 `$$$` 分隔；线路内剧集 `#` 分隔；
剧集 `名称$地址`）。**与苹果 CMS 的 play_url 拼装约定完全一致**，可复用现有切分逻辑。
实测 episode-id 形态：`avid_cid`（B站）、`base64(json)`（哇哇，`playerContent` 内解码）、
`sourceCode@rawUrl`（一一）——均为透传字符串，客户端原样回传即可。

**playerContent 返回**：`parse=0` 直链直接播；`parse=1` 需走解析/`parses` 链路。
可附带 `header`（播直链时透传 Referer/UA，见 §7）、`danmaku`（弹幕地址，B站源有）等扩展字段。

## 4. 模式B：独立文件（3 个文件，路由逻辑已读完）

### 4.1 B-1：`ac` 路由风格（`B__4_f6wc.php`、`B__6__5_viwx.php`）

入口：`$ac = $_GET['ac'] ?? 'detail'`，另读 `t/pg/f/ids/wd/flag/id`。

| ac | 条件 | 含义 | 请求示例 | 返回 |
|---|---|---|---|---|
| `detail` | 带 `ids` | 详情 | `xxx.php?ac=detail&ids=12345` | `{"list":[{vod_id,vod_name,vod_pic,vod_content,vod_play_from,vod_play_url}]}` |
| `detail` | 带 `t` | 分类列表 | `xxx.php?ac=detail&t=沙雕动画&pg=1`（`f` 可带筛选 JSON） | `{"list":[...], "page":N, "pagecount":N, "limit":20, "total":N}` |
| `detail` | 均无 | 首页 | `xxx.php?ac=detail` 或 `xxx.php` | `{"class":[...], "list":[...]}`（分类＋推荐合并） |
| `search` | `wd` | 搜索 | `xxx.php?ac=search&wd=关键词&pg=1` | 同分类列表结构 |
| `play` | `id`（`flag` 可选） | 播放解析 | `xxx.php?ac=play&flag=B站视频&id=123_456` | `{"parse":0,"url":"...","header":{...}}` |

注：`B__6__5_viwx.php` 头注释"使用 Apple CMS 标准参数"指**参数名**（ac/t/pg/ids/wd）
沿用 CMS 叫法，**返回格式不是 CMS 格式**（无 `code/msg` 包裹）。

### 4.2 B-2：无 `ac` 风格（`_____8_lotv.php` 小鸭看看）

按参数存在性依次判断：

| 条件（优先级从高到低） | 含义 | 请求示例 | 返回 |
|---|---|---|---|
| 带 `play` | 播放解析（`play` 以 http 开头则 `parse=0` 否则 `parse=1`） | `xxx.php?play=<url或id>` | `{"parse":0\|1,"url":"...","header":{"User-Agent":...}}` |
| 带 `ids` | 详情 | `xxx.php?ids=abc123` | `{"list":[{vod_id,vod_name,vod_pic,vod_content,vod_play_from,vod_play_url}]}` |
| 带 `wd` | 搜索 | `xxx.php?wd=关键词` | `{"list":[...]}` |
| 均无 | 分类/首页 | `xxx.php?t=10&pg=1`（`ext/class/filter` 可覆盖 `t`） | `{"class":[...],"filters":{...},"list":[...],"page":N,"pagecount":N,"limit":20,"total":N}` |

## 5. 模式A 的 HTTP 调度（`run()`）——高置信度推断，待最终确认

`spider.php` 源码未拿到（公开仓库对应路径 404，未做 URL 猜测式遍历）。
以下根据**两份同生态独立复刻**（`B__4_f6wc.php`、`B__6__5_viwx.php` 的路由代码）
＋ drpy-node/DZ 指南文档交叉推断，置信度高但非实锤：

| 客户端请求 | 推断映射的方法 | 置信度 |
|---|---|---|
| `GET {api}`（无参或 `ac=home`） | `init(ext)` → `homeContent` ＋ `homeVideoContent` 合并为 `{class, filters?, list}` | 高 |
| `GET {api}?ac=detail&t={tid}&pg={n}&f={json}&ext={...}` | `categoryContent($tid,$pg,$filter,$extend)` | 高（两份复刻一致） |
| `GET {api}?ac=detail&ids={id}` | `detailContent([$id])` | 高 |
| `GET {api}?ac=search&wd={key}&pg={n}` | `searchContent($key,false,$pg)` | 高 |
| `GET {api}?ac=play&flag={f}&id={id}` | `playerContent($flag,$id,[])`（或 `playContent`） | 高 |
| `$_GET['ext']` | 透传给 `init($extend)` | 中（按 TVBox spider 惯例；本站点配置均无 ext，暂不阻塞） |

**必须最终确认的两点**：① `run()` 是否还支持 `ac=category` 独立动作；
② `playerContent`/`playContent` 双命名兼容的具体写法。建议对照 `lib/spider.php`
源码或对用户实机 `127.0.0.1:19978` 做一次抓包验证（5 个请求即可定死）。

## 6. 与苹果 CMS 的对比、CmsApi 能否复用

| 维度 | Apple CMS（`api.php/provide/vod`） | type=4 PHP 站 |
|---|---|---|
| 参数名 | `ac=list/detail`，`t/pg/wd/ids` | `ac=detail/search/play`，`t/pg/f/ids/wd/flag/id`（部分重合） |
| 首页 | 无（CMS 无首页概念） | 有（`class` 分类＋推荐 `list`） |
| 返回包络 | `{"code":1,"msg":"...","page":..,"pagecount":..,"list":[...]}` | 无 `code/msg`；直接 `{class/list}` 或 `{list,page,pagecount,limit,total}` |
| vod 字段 | `vod_id/vod_name/vod_pic/vod_remarks/vod_play_from/vod_play_url` | 完全相同 |
| 播放串拼装 | `$$$` / `#` / `$` | 完全相同 |

**结论：`CmsApi` 不能直接复用**（包络不同：无 `code` 字段、无 `ac=list` 动作、有首页 `class`
结构）。但 vod 字段名与播放串拼装约定一致，**详情→剧集的切分逻辑可复用**
（`SiteRepository.parseEpisodes` / `CmsApi.parsePlayUrl` 那套 `$$$`/`#`/`$` 解析）。

## 7. 给 MovieShell 的实现建议（只写文档，不写代码）

1. **新增 `Type4Api`（或 `PhpSpiderApi`）**，与 `CmsApi` 并列，`SiteRepository` 增加
   `type==4` 分支：`isType4Site() = type == 4`。注意 `api` 可能是**相对路径或裸脚本名**
   的远端站，同样要做 URL 规范化（`HttpUrl` 解析＋百分号编码，文件名含空格/`ᵈᶻ`）。
2. **请求映射**（按 §5 主约定实现，`§4.2` 的 B-2 作为降级探测）：
   - `home(site)`：`GET api` → 取 `class`→`CategoryType(type_id/type_name)`，
     `filters`→ 暂存（供后续筛选 UI，首版可忽略），`list`→`Vod` 列表。
     `vod` 映射：`vod_id→id（转字符串）`、`vod_name→name`、`vod_pic→pic`（`//` 开头补 `https:`）、
     `vod_remarks→remarks`。
   - `category(site, tid, pg)`：`GET api?ac=detail&t={urlenc tid}&pg={pg}` →
     `{list,page,pagecount,limit,total}`；`pagecount<1` 按 1 容错。
   - `search(site, wd)`：`GET api?ac=search&wd={urlenc wd}&pg=1`（`searchable=0` 跳过）。
   - `detail(site, vodId)`：`GET api?ac=detail&ids={urlenc vodId}` → 取 `list[0]` 组 `VodDetail`，
     剧集切分**复用** `CmsApi.parsePlayUrl`（`$$$`/`#`/`$`）。
   - `play(site, flag, id)`：`GET api?ac=play&flag={urlenc flag}&id={urlenc id}` →
     `parse==0` 取 `url` 直播（`header` 中的 Referer/UA 透传给 ExoPlayer，
     复用 v0.5 的嗅探 header 通道）；`parse==1` 走现有解析/`parses` 链路（v0.5.1  noted 缺口，
     保持现状或弹提示）。
3. **方言探测**（应对 B-2 等变体）：站点接入时发一次 `GET api` 探测——返回含 `class`
   即主约定；若分类请求返回 `{"error":...}` 则依次试 `?t=&pg=`（B-2）、`?ac=category&t=`；
   探测结果缓存到站点元数据，避免每次请求试错。
4. **`quickSearch`/聚合搜索**：type=4 站 `searchable=1` 的直接纳入 `searchAll`，
   与 CMS/drpy/PY 站同等对待（单站超时 15s，失败静默跳过）。
5. **点播页当前源**：v0.5.1 的订阅当前源机制可直接复用 type=4 站点
  （`home`/`category` 已有统一签名）。
6. **"类型"字段**：`类型=小说/漫画` 的站，详情与播放另有约定
  （漫画 `playerContent` 返回 `pics://url1&&url2...`，见 drpy-node 文档 §6.1）——
   首版可先按视频站实现，小说/漫画标记为"暂不支持该类型"而非静默失败。
7. **安全**：type=4 的 `api` 指向用户手机本地或任意远端 PHP，返回的 `url` 仅用于播放，
   不执行；`header` 只取白名单字段（User-Agent/Referer/Origin/Cookie）。
8. **验证清单**（对着用户实机做）：`GET /config.php`→25 站；任选一站
   `GET xxx.php`（首页 class+list）、`?ac=detail&t=<首个type_id>&pg=1`、
   `?ac=search&wd=xxx`、`?ac=detail&ids=<vod_id>`、`?ac=play&flag=<f>&id=<episodeId>`，
   5 步全通即协议定死。

## 8. 网上调研结论

- 找到官方框架文档：hjdhnx/drpy-node `spider/php/readme.md`（确认为同一框架：
  `(new Spider())->run()`、`lib/spider.php`、`ᵈᶻ.php` 命名规范、`pageResult`、
  `pdfa/pdfh/pd` 均对上）及《PHP Spider 开发与维护指南 (DZ 风格)》。
- **未找到 `run()` 源码**：公开仓库 `spider/php/lib/spider.php` 路径返回 404，
  未做进一步 URL 试探。§5 的调度表为高置信度推断，待源码或抓包确认。
- 实测 7 文件与文档的方法签名、返回契约完全一致，文档可作为实现时的第二证据源。

## §8 run() 调度确认补记（2026-10-12，用户提供 spider.php 源码，"Copyright 道长所有 Date: 2026/01/23"）

BaseSpider::run() 精确调度逻辑（不再是推断）：
- 每次请求先执行 `$this->init($extend)`；`$extend` = `$_GET['ext']` 经 base64→JSON 解码（解出数组则用数组）。
- `ac=action` → `action($_GET['action'], $_GET['value'])`
- `ac=play` 或 `$_GET['play']` 非空 → `playContent($flag, $playId)`，其中 `$playId = $_GET['play'] ?: $_GET['id']`
- `$_GET['wd']` 非空 → `searchContent($wd, false, $pg)`
- `$_GET['ids']` 非空 **且** `ac` 非空 → `detailContent(explode(',', $ids))`
- `$_GET['t'] !== ''` **且** `ac` 非空 → `categoryContent($t, $pg, [], $extend)`
- 无参数 → `homeContent($filter)` + `homeVideoContent()` 合并为 `{class, list, filters?}`
- 异常 → `{"code":500,"msg":"..."}`

App 侧 type=4（模式A）请求映射（定稿）：
- 首页：`GET {api}` → `{class:[{type_id,type_name}], list:[vod...]}`
- 分类：`GET {api}?ac=detail&t={tid}&pg={pg}` → `{list,page,pagecount,limit,total}`
- 搜索：`GET {api}?wd={keyword}&pg={pg}` → `{list}`
- 详情：`GET {api}?ac=detail&ids={vodId}` → `{list:[vod]}`；`vod_play_from`/`vod_play_url` 按 `$$$` / `#` / `$` 切分（同苹果 CMS）
- 播放：`GET {api}?ac=play&flag={flag}&id={playId}` → `{parse:0|1, url, header}`；`parse=1` 走解析链路；`header` 透传给播放器
- 注意：`t` 参数是"非空字符串"判断（`!== ''`），`ac` 只要非空即可；分类请求用 `ac=detail` 占位即可

## §9 端口预留约定（2026-10-12 用户硬性要求）

未来 MovieShell 若内置本地服务（含 PHP），端口必须避开用户自用应用和主流 App 已占用的端口：
- 19978：用户 DsPlayer 的 T4 PHP 插件
- 17890：magnet-stream 磁力转播服务
- 9978：TVBox 系文件服务
- 另避开 8080 / 8000 / 8888 等常见端口

**未来内置 PHP 服务预留端口：29578。**

注：v0.6.0 的 type=4 为纯客户端实现（只对外连 PHP 脚本 HTTP 地址，不在本地开端口），不受本条影响。
